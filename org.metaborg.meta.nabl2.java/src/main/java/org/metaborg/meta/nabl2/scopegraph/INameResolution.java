package org.metaborg.meta.nabl2.scopegraph;

import org.metaborg.meta.nabl2.scopegraph.path.IDeclPath;
import org.metaborg.meta.nabl2.scopegraph.path.IResolutionPath;
import org.metaborg.util.iterators.Iterables2;

public interface INameResolution<S extends IScope, L extends ILabel, O extends IOccurrence> {

    Iterable<S> getAllScopes();

    Iterable<O> getAllRefs();

    Iterable<IResolutionPath<S, L, O>> resolve(O ref);

    Iterable<IDeclPath<S, L, O>> visible(S scope);

    Iterable<IDeclPath<S, L, O>> reachable(S scope);

    static <S extends IScope, L extends ILabel, O extends IOccurrence> INameResolution<S, L, O> empty() {
        return new INameResolution<S, L, O>() {

            @Override public Iterable<S> getAllScopes() {
                return Iterables2.empty();
            }

            @Override public Iterable<O> getAllRefs() {
                return Iterables2.empty();
            }

            @Override public Iterable<IResolutionPath<S, L, O>> resolve(O ref) {
                return Iterables2.empty();
            }

            @Override public Iterable<IDeclPath<S, L, O>> visible(S scope) {
                return Iterables2.empty();
            }

            @Override public Iterable<IDeclPath<S, L, O>> reachable(S scope) {
                return Iterables2.empty();
            }

        };
    }

}