package mb.statix.actors;

import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;

import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;

import io.usethesource.capsule.SetMultimap;
import mb.statix.actors.deadlock.CanDeadlock;
import mb.statix.actors.deadlock.DeadlockMonitor;
import mb.statix.actors.deadlock.IDeadlockMonitor;

public class DeadlockTest {

    private static final ILogger logger = LoggerUtils.logger(DeadlockTest.class);

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        final ActorSystem system = new ActorSystem();
        TypeTag<IDeadlockMonitor<String>> tt = TypeTag.of(IDeadlockMonitor.class);
        final IActor<IDeadlockMonitor<String>> dlm = system.add("dlm", tt, self -> new DeadlockMonitor<>(self));
        final IActor<IPingPong> pp1 = system.add("pp1", TypeTag.of(IPingPong.class), self -> new PingPong(self, dlm));
        pp1.addMonitor(dlm);
//        final IActor<IPingPong> pp2 = system.add("pp2", TypeTag.of(IPingPong.class), self -> new PingPong(self, dlm));
//        pp2.addMonitor(dlm);
        system.start();
        system.async(pp1).start(pp1);
        //        system.async(pp1).start(pp2);
        //        system.async(pp2).start(pp1);
    }

    interface IPing {

        void ping(IActorRef<? extends IPong> source);

    }

    interface IPong {

        void pong(IActorRef<? extends IPong> source);

    }

    interface IPingPong extends IPing, IPong, CanDeadlock<String> {

        void start(IActorRef<? extends IPing> target);

    }

    private static class PingPong implements IPingPong {

        private final IActor<IPingPong> self;
        private final IActorRef<IDeadlockMonitor<String>> dlm;

        public PingPong(IActor<IPingPong> self, IActorRef<IDeadlockMonitor<String>> dlm) {
            this.self = self;
            this.dlm = dlm;
        }

        @Override public void start(IActorRef<? extends IPing> target) {
            logger.info("{} started", self);
            self.async(target).ping(self);
            self.async(dlm).waitFor(self, "pong", target);
        }

        @Override public void ping(IActorRef<? extends IPong> source) {
            logger.info("{} received ping from {}", self, source);
            //            source.get().pong(self);

        }

        @Override public void pong(IActorRef<? extends IPong> source) {
            logger.info("{} recieved pong from {}", self, source);
            self.async(dlm).granted(self, "pong", source);
            self.stop();
        }

        @Override public void deadlocked(SetMultimap<IActorRef<?>, String> waitFors) {
            logger.error("{} detected deadlock: {}", self, waitFors);
            for(Entry<IActorRef<?>, String> waitFor : waitFors.entrySet()) {
                // reply after all
                self.async((IActorRef<? extends IPong>) waitFor.getKey()).pong(self);
            }
        }

    }

}