package com.instaclustr.cassandra.bloom.idx.mem.tables;

import static com.instaclustr.cassandra.bloom.idx.mem.tables.AbstractTableTestHelpers.assertNoLocks;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;

import org.junit.Test;

public class MultiThreadedTests {

    public MultiThreadedTests() {
        // TODO Auto-generated constructor stub
    }

    // base for test callables
    abstract class B implements Callable<Boolean> {
        public boolean running = true;
        final BusyTable busy;
        final ExecutorService executor;
        boolean ranOnce = false;
        int idx;
        Callable<Boolean> isSet=new Callable<Boolean>(){@Override public Boolean call()throws IOException{return busy.isSet(idx);}};
        private final int id;

        B(int id, BusyTable busy, ExecutorService executor) {
            this.id = id;
            this.busy = busy;
            this.executor = executor;
        }

        @Override
        public String toString() {
            return this.getClass().getSimpleName() + "-" + id;
        }
    }

    // reader callable
    class R extends B {
        public boolean status;

        R(int id, BusyTable busy, ExecutorService executor, int pos) {
            super( id, busy, executor );
            this.idx = pos;
        }

        @Override
        public Boolean call() {
            try {
                while (running) {
                    status = executor.submit(isSet).get(1, TimeUnit.SECONDS);
                    while (running && (status == executor.submit(isSet).get(1, TimeUnit.SECONDS))) {
                        ranOnce = true;
                        Thread.yield();
                    }
                }
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                System.out.println( String.format("%s watching position %s has %s", this, idx, e));
                e.printStackTrace();
                fail();
            }
            return ranOnce;
        }
    }

    // writer callable
    class W extends B {

        int loops;
        IntConsumer consumer;

        Callable<Object> clear=new Callable<Object>(){@Override public Object call()throws IOException{busy.clear(idx);return"";}};

        W(int id, BusyTable busy, ExecutorService executor, int loops, IntConsumer consumer) {
            super( id, busy, executor );
            this.loops = loops;
            this.consumer = consumer;
        }

        @Override
        public Boolean call() {
            while (loops > 0) {
                try {
                    idx = executor.submit(busy::newIndex).get(1, TimeUnit.SECONDS);
                    consumer.accept(idx);
                    Thread.sleep(40);
                    // since we created the idx nobody else should be able to disable it
                    if (! busy.isSet(idx)) {
                        System.out.println( String.format("%s idx %s was reset while we held it", this, idx));
                        fail();
                    }

                    executor.submit(clear).get(1, TimeUnit.SECONDS);
                    Thread.sleep(40);
                    ranOnce = true;
                    loops--;
                } catch (TimeoutException | InterruptedException | ExecutionException | IOException e) {
                    System.out.println( String.format("%s exception writing: %s", this, e));
                    e.printStackTrace();
                    fail();
                }
            }
            return ranOnce;

        }

    }

    @Test
    public void multiThreadedTest() throws Exception {
        int threadCount = 50;
        ExecutorService executor = Executors.newCachedThreadPool();
        Set<Integer> indexes = new HashSet<Integer>();

        try (BusyTable busy = new BusyTable(file)) {

            List<B> lst = new ArrayList<B>();
            lst.add(new R(1,busy, executor, 5));
            lst.add(new R(2,busy, executor, 6));
            for (int i = 0; i < threadCount; i++) {
                lst.add(new W(i+1,busy, executor, 5, indexes::add));
            }

            List<Future<Boolean>> futures = new ArrayList<Future<Boolean>>();
            lst.forEach(b -> futures.add(executor.submit(b)));
            Thread.sleep(3000);
            lst.forEach(b -> b.running = false);
            boolean hasFailures = false;
            for (int i=0;i<futures.size();i++) {
                Future<Boolean> f = futures.get(i);
                try {
                    if (f.isDone()) {
                        if (f.isCancelled()) {
                            System.out.println( String.format(" %s cancelled", lst.get(i) ));
                            hasFailures=true;
                        } else {
                            if (!f.get()) {
                                System.out.println(String.format(" %s failed", lst.get(i) ));
                                hasFailures = true;
                            }
                        }
                    } else {
                        assertTrue(f.get(5, TimeUnit.SECONDS));
                    }

                } catch (InterruptedException | ExecutionException |TimeoutException e) {
                    hasFailures = true;
                    System.out.println( String.format(" %s exception: %s", lst.get(i), e ));
                }
            }
            executor.shutdown();
            assertNoLocks(busy);
            lst.forEach(b -> assertTrue(b.toString() + " did not run once", b.ranOnce));
            System.out.println( String.format( "%s threads executed on %s indexes", threadCount, indexes.size() ));
            assertTrue( "Did not test with lock collisions", indexes.size() > 40 );
            assertFalse( "Failues listed in console", hasFailures);
        }
    }
}