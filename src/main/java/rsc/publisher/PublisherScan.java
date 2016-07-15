package rsc.publisher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.BiFunction;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import rsc.flow.Loopback;
import rsc.flow.Producer;
import rsc.flow.Receiver;
import rsc.flow.Trackable;
import rsc.util.BackpressureHelper;
import rsc.util.ExceptionHelper;
import rsc.subscriber.SubscriptionHelper;
import rsc.util.UnsignalledExceptions;

/**
 * Aggregates the source values with the help of an accumulator function
 * and emits the intermediate results.
 * <p>
 * The accumulation works as follows:
 * <pre><code>
 * result[0] = initialValue;
 * result[1] = accumulator(result[0], source[0])
 * result[2] = accumulator(result[1], source[1])
 * result[3] = accumulator(result[2], source[2])
 * ...
 * </code></pre>
 *
 * @param <T> the source value type
 * @param <R> the aggregate type
 */
public final class PublisherScan<T, R> extends PublisherSource<T, R> {

    final BiFunction<R, ? super T, R> accumulator;

    final R initialValue;

    public PublisherScan(Publisher<? extends T> source, R initialValue, BiFunction<R, ? super T, R> accumulator) {
        super(source);
        this.accumulator = Objects.requireNonNull(accumulator, "accumulator");
        this.initialValue = Objects.requireNonNull(initialValue, "initialValue");
    }

    @Override
    public void subscribe(Subscriber<? super R> s) {
        source.subscribe(new PublisherScanSubscriber<>(s, accumulator, initialValue));
    }

    static final class PublisherScanSubscriber<T, R>
      implements Subscriber<T>, Subscription, Producer, Receiver, Loopback, Trackable {

        final Subscriber<? super R> actual;

        final BiFunction<R, ? super T, R> accumulator;

        Subscription s;

        R value;

        boolean done;

        /**
         * Indicates the source completed and the value field is ready to be emitted.
         * <p>
         * The AtomicLong (this) holds the requested amount in bits 0..62 so there is room
         * for one signal bit. This also means the standard request accounting helper method doesn't work.
         */
        static final long COMPLETED_MASK = 0x8000_0000_0000_0000L;

        static final long REQUESTED_MASK = Long.MAX_VALUE;

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<PublisherScanSubscriber> REQUESTED =
          AtomicLongFieldUpdater.newUpdater(PublisherScanSubscriber.class, "requested");

        long produced;
        
        public PublisherScanSubscriber(Subscriber<? super R> actual, BiFunction<R, ? super T, R> accumulator,
                                       R initialValue) {
            this.actual = actual;
            this.accumulator = accumulator;
            this.value = initialValue;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;

                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            if (done) {
                UnsignalledExceptions.onNextDropped(t);
                return;
            }

            R r = value;

            produced++;
            
            actual.onNext(r);

            try {
                r = accumulator.apply(r, t);
            } catch (Throwable e) {
                s.cancel();
                ExceptionHelper.throwIfFatal(e);
                onError(ExceptionHelper.unwrap(e));

                return;
            }

            if (r == null) {
                s.cancel();

                onError(new NullPointerException("The accumulator returned a null value"));
                return;
            }

            value = r;
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                UnsignalledExceptions.onErrorDropped(t);
                return;
            }
            done = true;
            actual.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;

            R v = value;
            
            long p = produced;
            long r = requested;
            if (r != Long.MAX_VALUE && p != 0L) {
                r = REQUESTED.addAndGet(this, -p);
            }

            for (; ; ) {
                // if any request amount is still available, emit the value and complete
                if ((r & REQUESTED_MASK) != 0L) {
                    actual.onNext(v);
                    actual.onComplete();
                    return;
                }
                // NO_REQUEST_NO_VALUE -> NO_REQUEST_HAS_VALUE
                if (REQUESTED.compareAndSet(this, 0, COMPLETED_MASK)) {
                    return;
                }
                
                r = requested;
            }
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                for (;;) {

                    long r = requested;

                    // NO_REQUEST_HAS_VALUE 
                    if (r == COMPLETED_MASK) {
                        // any positive request value will do here
                        // transition to HAS_REQUEST_HAS_VALUE
                        if (REQUESTED.compareAndSet(this, COMPLETED_MASK, COMPLETED_MASK | 1)) {
                            actual.onNext(value);
                            actual.onComplete();
                        }
                        return;
                    }

                    // HAS_REQUEST_HAS_VALUE
                    if (r < 0L) {
                        return;
                    }

                    // transition to HAS_REQUEST_NO_VALUE
                    long u = BackpressureHelper.addCap(r, n);
                    if (REQUESTED.compareAndSet(this, r, u)) {
                        s.request(n);
                        return;
                    }
                }
            }
        }

        @Override
        public void cancel() {
            s.cancel();
        }

        @Override
        public boolean isStarted() {
            return s != null && !done;
        }

        @Override
        public long requestedFromDownstream() {
            return requested - produced;
        }

        @Override
        public boolean isTerminated() {
            return done;
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public Object connectedInput() {
            return accumulator;
        }

        @Override
        public Object upstream() {
            return s;
        }
    }
}
