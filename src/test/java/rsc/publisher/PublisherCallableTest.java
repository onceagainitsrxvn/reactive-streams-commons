package rsc.publisher;

import java.io.IOException;

import org.junit.Test;
import rsc.test.TestSubscriber;

public class PublisherCallableTest {

    @Test(expected = NullPointerException.class)
    public void nullCallable() {
        new PublisherCallable<Integer>(null);
    }

    @Test
    public void callableReturnsNull() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();

        new PublisherCallable<Integer>(() -> null).subscribe(ts);

        ts.assertNoValues()
          .assertNotComplete()
          .assertError(NullPointerException.class);
    }

    @Test
    public void normal() {
        TestSubscriber<Integer> ts = new TestSubscriber<>();

        new PublisherCallable<>(() -> 1).subscribe(ts);

        ts.assertValue(1)
          .assertComplete()
          .assertNoError();
    }

    @Test
    public void normalBackpressured() {
        TestSubscriber<Integer> ts = new TestSubscriber<>(0);

        new PublisherCallable<>(() -> 1).subscribe(ts);

        ts.assertNoValues()
          .assertNotComplete()
          .assertNoError();

        ts.request(1);

        ts.assertValue(1)
          .assertComplete()
          .assertNoError();
    }

    @Test
    public void callableThrows() {
        TestSubscriber<Object> ts = new TestSubscriber<>();

        new PublisherCallable<>(() -> {
            throw new IOException("forced failure");
        }).subscribe(ts);

        ts.assertNoValues()
          .assertNotComplete()
          .assertError(IOException.class)
          .assertErrorMessage("forced failure");
    }
}
