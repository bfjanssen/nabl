package mb.statix.solver.concurrent;

import mb.statix.actors.futures.IFuture;

public interface ITypeChecker<S, L, D, R> {

    IFuture<R> run(S root) throws InterruptedException;

}