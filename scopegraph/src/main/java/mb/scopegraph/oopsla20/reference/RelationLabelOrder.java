package mb.scopegraph.oopsla20.reference;

import mb.scopegraph.relations.IRelation;

public class RelationLabelOrder<L> implements LabelOrder<L> {

    private final IRelation<EdgeOrData<L>> labelOrd;

    public RelationLabelOrder(IRelation<EdgeOrData<L>> labelOrd) {
        this.labelOrd = labelOrd;
    }

    @Override public boolean lt(EdgeOrData<L> l1, EdgeOrData<L> l2) throws ResolutionException, InterruptedException {
        return labelOrd.contains(l1, l2);
    }

    @Override public String toString() {
        return labelOrd.toString();
    }

}