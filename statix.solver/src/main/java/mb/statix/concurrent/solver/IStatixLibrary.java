package mb.statix.concurrent.solver;

import static mb.nabl2.terms.build.TermBuild.B;
import static mb.nabl2.terms.matching.TermMatch.M;

import java.util.List;
import java.util.Set;

import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.matching.TermMatch.IMatcher;
import mb.nabl2.terms.unification.u.PersistentUnifier;
import mb.statix.scopegraph.IScopeGraph;
import mb.statix.scopegraph.terms.Scope;
import mb.statix.spoofax.StatixTerms;

public interface IStatixLibrary {

    List<Scope> rootScopes();

    Set<Scope> ownScopes();

    IScopeGraph.Immutable<Scope, ITerm, ITerm> scopeGraph();

    static IMatcher<IStatixLibrary> matcher() {
        return M.appl3("Library", M.listElems(Scope.matcher()), M.listElems(Scope.matcher()), StatixTerms.scopeGraph(),
                (t, rootScopes, ownScopes, scopeGraph) -> StatixLibrary.of(rootScopes, ownScopes, scopeGraph));
    }

    static ITerm toTerm(IStatixLibrary library) {
        return B.newAppl("Library", B.newList(library.rootScopes()), B.newList(library.ownScopes()),
                StatixTerms.toTerm(library.scopeGraph(), PersistentUnifier.Immutable.of()));
    }

}