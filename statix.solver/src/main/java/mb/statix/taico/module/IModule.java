package mb.statix.taico.module;

import java.util.Set;
import java.util.stream.StreamSupport;

import mb.nabl2.terms.ITerm;
import mb.statix.taico.paths.IQuery;
import mb.statix.taico.scopegraph.IMInternalScopeGraph;
import mb.statix.taico.scopegraph.IOwnableScope;

/**
 * Interface to represent a module.
 */
public interface IModule {
    String getId();
    
    Set<IQuery<IOwnableScope, ITerm, ITerm>> queries();
    
    IModule getParent();
    
    Set<IModule> getChildren();
    
    IMInternalScopeGraph<IOwnableScope, ITerm, ITerm> getScopeGraph();
    
    /**
     * @return
     *      all the modules that are descendent from this module
     */
    default Iterable<IModule> getDescendants() {
        return getChildren().stream()
                .flatMap(m -> StreamSupport.stream(m.getDescendants().spliterator(), false))
                ::iterator;
    }
}
