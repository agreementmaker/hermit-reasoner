// Copyright 2008 by Oxford University; see license.txt for details
package org.semanticweb.HermiT.blocking;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.semanticweb.HermiT.blocking.ValidatedDirectBlockingChecker.ValidatedBlockingObject;
import org.semanticweb.HermiT.model.AtomicConcept;
import org.semanticweb.HermiT.model.AtomicRole;
import org.semanticweb.HermiT.model.Concept;
import org.semanticweb.HermiT.model.DLClause;
import org.semanticweb.HermiT.model.Variable;
import org.semanticweb.HermiT.model.DLClause.ClauseType;
import org.semanticweb.HermiT.monitor.TableauMonitor;
import org.semanticweb.HermiT.tableau.DLClauseEvaluator;
import org.semanticweb.HermiT.tableau.ExtensionManager;
import org.semanticweb.HermiT.tableau.Node;
import org.semanticweb.HermiT.tableau.NodeType;
import org.semanticweb.HermiT.tableau.Tableau;

public class AnywhereValidatedBlocking implements BlockingStrategy {
    protected final DirectBlockingChecker m_directBlockingChecker;
    protected final ValidatedBlockersCache m_currentBlockersCache;
    protected final BlockingSignatureCache m_blockingSignatureCache;
    protected BlockingValidator m_blockingValidator;
    //protected BlockingValidator m_blockingValidatorRules;
    protected Tableau m_tableau;
    protected ExtensionManager m_extensionManager;
    protected Node m_firstChangedNode;
    protected Node m_lastValidatedUnchangedNode=null;
    protected boolean m_useSingletonCore;
    protected Map<AtomicConcept, Set<Set<Concept>>> m_unaryValidBlockConditions;
    protected Map<Set<AtomicConcept>, Set<Set<Concept>>> m_nAryValidBlockConditions;
    protected final boolean m_hasInverses;
    protected final boolean m_validateViaRuleApplicability;
    
    // statistics: 
    protected int numDirectlyBlocked=0;
    protected int numIndirectlyBlocked=0;
    protected final boolean debuggingMode=false;
    protected int numberOfValidationsForThisModel;
    protected boolean plotting=false;
    
    public AnywhereValidatedBlocking(DirectBlockingChecker directBlockingChecker,BlockingSignatureCache blockingSignatureCache,Map<AtomicConcept, Set<Set<Concept>>> unaryValidBlockConditions, Map<Set<AtomicConcept>, Set<Set<Concept>>> nAryValidBlockConditions, boolean hasInverses,boolean useSingletonCore,boolean validateViaRuleApplicability) {
        m_directBlockingChecker=directBlockingChecker;
        m_currentBlockersCache=new ValidatedBlockersCache(m_directBlockingChecker);
        m_blockingSignatureCache=blockingSignatureCache;
        m_unaryValidBlockConditions=unaryValidBlockConditions;
        m_nAryValidBlockConditions=nAryValidBlockConditions;
        m_hasInverses=hasInverses;
        m_useSingletonCore=useSingletonCore;
        m_validateViaRuleApplicability=validateViaRuleApplicability;
    }
    public void initialize(Tableau tableau) {
        m_tableau=tableau;
        m_directBlockingChecker.initialize(tableau);
        m_extensionManager=m_tableau.getExtensionManager();
        if (m_validateViaRuleApplicability) {
            m_blockingValidator=new BlockingValidatorRules(m_tableau);
        } else {
            m_blockingValidator=new BlockingValidatorConstraints(m_tableau,m_directBlockingChecker,m_unaryValidBlockConditions,m_nAryValidBlockConditions,m_hasInverses);
           // m_blockingValidatorRules=new BlockingValidatorRules(m_tableau);
        }
        numberOfValidationsForThisModel=0;
    }
    public void clear() {
        m_currentBlockersCache.clear();
        m_firstChangedNode=null;
        m_directBlockingChecker.clear();
        m_lastValidatedUnchangedNode=null;
        numberOfValidationsForThisModel=0;
    }
    public void computeBlocking(boolean finalChance) {
        if (finalChance) {
            validateBlocks();
        } else {
            computePreBlocking();
        }
    }
    public void computePreBlocking() {
        if (m_firstChangedNode!=null) {
            Node node=m_firstChangedNode;
            while (node!=null) {
                m_currentBlockersCache.removeNode(node);
                node=node.getNextTableauNode();
            }
            node=m_firstChangedNode;
            boolean checkBlockingSignatureCache=(m_blockingSignatureCache!=null && !m_blockingSignatureCache.isEmpty());
            while (node!=null) {
                if (node.isActive()) {
                    if (node.hasUnprocessedExistentials() 
                                && (m_directBlockingChecker.canBeBlocked(node)) 
                                && (m_directBlockingChecker.hasBlockingInfoChanged(node) || !node.isDirectlyBlocked() || node.getBlocker().getNodeID()>=m_firstChangedNode.getNodeID())) {
                        Node parent=node.getParent();
                        if (parent==null)
                            node.setBlocked(null,false);
                        else if (parent.isBlocked())
                            node.setBlocked(parent,false);
                        else if (checkBlockingSignatureCache && m_blockingSignatureCache.containsSignature(node)) {
                            node.setBlocked(Node.SIGNATURE_CACHE_BLOCKER,true);
                        } else {
                            Node blocker=null;
                            Node previousBlocker=node.getBlocker();
                            List<Node> possibleBlockers=m_currentBlockersCache.getPossibleBlockers(node);
                            if (!possibleBlockers.isEmpty()) {
                                if (m_directBlockingChecker.hasChangedSinceValidation(node) || m_directBlockingChecker.hasChangedSinceValidation(node.getParent())) {
                                    // the node or its parent has changed since we last validated the blocks, so even if all the blockers 
                                    // in the cache were invalid last time we validated, we'll give it another try
                                    blocker=possibleBlockers.get(0);
                                } else {
                                    // neither the node nor its parent has not changed since the last validation
                                    // if also the possible blockers in the blockers cache and their parents have not changed
                                    // since the last validation, there is no point in blocking again
                                    // the only exception is that the blockers cache contains the node that blocked this node previously 
                                    // if that node and its parent is unchanged, then the block is still ok 
                                    // if its previous blocker is still in the cache then it has also not been modified because 
                                    // it would have a different hash code after the modification
                                    // if the previous blocker is no longer there, then it does not make sense to try any node with smaller 
                                    // node ID again unless it has been modified since the last validation (-> newly added to the cache), 
                                    if (previousBlocker!=null&&possibleBlockers.contains(previousBlocker)&&!m_directBlockingChecker.hasChangedSinceValidation(previousBlocker)&&!m_directBlockingChecker.hasChangedSinceValidation(previousBlocker.getParent())) {
                                        // reassign the valid and unchanged blocker
                                        blocker=previousBlocker;
                                    } else {
                                        for (Node n : possibleBlockers) {
                                            // find he smallest one that has changed since we last validated
                                            if (m_directBlockingChecker.hasChangedSinceValidation(n) || m_directBlockingChecker.hasChangedSinceValidation(n.getParent())) {
                                                blocker=n;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                            node.setBlocked(blocker,blocker!=null);
                        }
                    }
                    if (!node.isBlocked() && m_directBlockingChecker.canBeBlocker(node))
                        m_currentBlockersCache.addNode(node);
                }
                m_directBlockingChecker.clearBlockingInfoChanged(node);
                node=node.getNextTableauNode();
            }
            m_firstChangedNode=null;
        }
    }
    public void validateBlocks() {
        // after first complete validation, we can switch to only checking block validity immediately

        // statistics:
        int checkedBlocks = 0;
        int invalidBlocks = 0;
        numberOfValidationsForThisModel++;
        Node firstInvalidlyBlockedNode=null;
        
        TableauMonitor monitor=m_tableau.getTableauMonitor();
        if (monitor!=null) monitor.blockingValidationStarted();
        
        Node node = m_lastValidatedUnchangedNode==null?m_tableau.getFirstTableauNode():m_lastValidatedUnchangedNode;
        Node firstValidatedNode=node;
        while (node!=null) {
            m_currentBlockersCache.removeNode(node);
            node=node.getNextTableauNode();
        }
        node=firstValidatedNode;
        if (debuggingMode) System.out.print("Model size: "+(m_tableau.getNumberOfNodesInTableau()-m_tableau.getNumberOfMergedOrPrunedNodes())+" Current ID:");
        while (node!=null) {
            if (node.isActive()) {
                if (node.isBlocked()) {
                    checkedBlocks++;
                    // check whether the block is a correct one
                    Node validBlocker;
                    if (node.isDirectlyBlocked() || !node.getParent().isBlocked()) {
                        if (m_directBlockingChecker.hasChangedSinceValidation(node) || m_directBlockingChecker.hasChangedSinceValidation(node.getParent()) || m_directBlockingChecker.hasChangedSinceValidation(node.getBlocker()) || m_directBlockingChecker.hasChangedSinceValidation(node.getBlocker().getParent())) {
                            List<Node> possibleBlockers = m_currentBlockersCache.getPossibleBlockers(node);
                            validBlocker=null;
                            if (!possibleBlockers.isEmpty()) {
                                int i=0;
                                if (node.getBlocker()!=null && possibleBlockers.contains(node.getBlocker())) {
                                    // we always assign the smallest node that has been modified since the last validation
                                    // re-testing smaller (unmodified) ones makes no sense 
                                    i=possibleBlockers.indexOf(node.getBlocker());
                                } else {
                                    // we have to try a completely new blocker
                                    m_blockingValidator.blockerChanged(node);
                                    //m_blockingValidatorRules.blockerChanged(node);
                                }
                                for (; i<possibleBlockers.size(); i++) {
                                    Node blocker=possibleBlockers.get(i);
                                    node.setBlocked(blocker,true);
                                    //boolean isValidRules=m_blockingValidatorRules.isBlockValid(node);
                                    if (m_blockingValidator.isBlockValid(node)) {
//                                        if (!isValidRules) {
//                                            System.out.println("rules false, constraints true");
//                                            ((BlockingValidatorRules)m_blockingValidatorRules).resetChildFlags(node.getParent());
//                                            ((ValidatedBlockingObject)node.getParent().getBlockingObject()).m_hasAlreadyBeenChecked=false;
//                                            m_blockingValidatorRules.isBlockValid(node);
//                                            m_blockingValidator.isBlockValid(node);
//                                        }
                                        validBlocker=blocker;
                                        break;
                                    } else {
//                                        if (isValidRules) {
//                                            System.out.println("rules true, constraints false");
//                                        }
                                        m_blockingValidator.blockerChanged(node);
//                                        m_blockingValidatorRules.blockerChanged(node);
                                    }
                                }
                            }
                            if (validBlocker == null) {
                                invalidBlocks++;
                                if (firstInvalidlyBlockedNode==null) firstInvalidlyBlockedNode=node;
                            }
                            node.setBlocked(validBlocker,validBlocker!=null);
                        } 
                    }
                }
                m_lastValidatedUnchangedNode=node;
                if (!node.isBlocked() && m_directBlockingChecker.canBeBlocker(node))
                    m_currentBlockersCache.addNode(node);
                if (debuggingMode && node.getNodeID() % 1000 == 0) System.out.print(" " + node.getNodeID());
            }
            node=node.getNextTableauNode();
        } 
        if (debuggingMode) System.out.println("");
        if (plotting) {
            System.out.println(numberOfValidationsForThisModel+" "+invalidBlocks+" i"); 
        }
        node=firstValidatedNode;
        while (node!=null) {
            if (node.isActive()) {
                m_directBlockingChecker.setHasChangedSinceValidation(node, false);
                ValidatedBlockingObject blockingObject=(ValidatedBlockingObject)node.getBlockingObject();
                blockingObject.setBlockViolatesParentConstraints(false);
                blockingObject.setHasAlreadyBeenChecked(false);
            }
            node=node.getNextTableauNode();
        }
        // if set to some node, then computePreblocking will be asked to check from that node onwards in case of invalid blocks 
        m_firstChangedNode=firstInvalidlyBlockedNode;
        if (monitor!=null) monitor.blockingValidationFinished();
        //m_firstChangedNode=firstValidatedNode;
        //m_firstChangedNode=null;
        if (debuggingMode) System.out.println("Checked " + checkedBlocks + " blocked nodes of which " + invalidBlocks + " were invalid.");
    }

    public boolean isPermanentAssertion(Concept concept,Node node) {
        return true;
    }
    protected void validationInfoChanged(Node node) {
        if (m_lastValidatedUnchangedNode!=null && node.getNodeID()<m_lastValidatedUnchangedNode.getNodeID()) {
            m_lastValidatedUnchangedNode=node;
        }
        m_directBlockingChecker.setHasChangedSinceValidation(node, true);
    }
    public void assertionAdded(Concept concept,Node node,boolean isCore) {
        if (m_directBlockingChecker.assertionAdded(concept,node,isCore)!=null || m_lastValidatedUnchangedNode!=null) updateNodeChange(node);
        validationInfoChanged(node);
    }
    public void assertionCoreSet(Concept concept,Node node) {
        if (m_directBlockingChecker.assertionAdded(concept,node,true)!=null || m_lastValidatedUnchangedNode!=null) updateNodeChange(node);
        validationInfoChanged(node);
    }
    public void assertionRemoved(Concept concept,Node node,boolean isCore) {
        if (m_directBlockingChecker.assertionRemoved(concept,node,isCore)!=null || m_lastValidatedUnchangedNode!=null) updateNodeChange(node);
        validationInfoChanged(node);
    }
    public void assertionAdded(AtomicRole atomicRole,Node nodeFrom,Node nodeTo,boolean isCore) {
        if (isCore || m_lastValidatedUnchangedNode!=null) updateNodeChange(nodeFrom);
        if (isCore || m_lastValidatedUnchangedNode!=null) updateNodeChange(nodeTo);
        validationInfoChanged(nodeFrom);
        validationInfoChanged(nodeTo);
    }
    public void assertionCoreSet(AtomicRole atomicRole,Node nodeFrom,Node nodeTo) {
        m_directBlockingChecker.assertionAdded(atomicRole,nodeFrom,nodeTo,true);
        if (m_lastValidatedUnchangedNode!=null) updateNodeChange(nodeFrom);
        if (m_lastValidatedUnchangedNode!=null) updateNodeChange(nodeTo);
        validationInfoChanged(nodeFrom);
        validationInfoChanged(nodeTo);
    }
    public void assertionRemoved(AtomicRole atomicRole,Node nodeFrom,Node nodeTo,boolean isCore) {
        m_directBlockingChecker.assertionRemoved(atomicRole,nodeFrom,nodeTo,true);
        if (isCore || m_lastValidatedUnchangedNode!=null) updateNodeChange(nodeFrom);
        if (isCore || m_lastValidatedUnchangedNode!=null) updateNodeChange(nodeTo);
        validationInfoChanged(nodeFrom);
        validationInfoChanged(nodeTo);
    }
    public void nodeStatusChanged(Node node) {
        updateNodeChange(node);
    }
    protected final void updateNodeChange(Node node) {
        if (node!=null) {
            if (m_firstChangedNode==null || node.getNodeID()<m_firstChangedNode.getNodeID())
                m_firstChangedNode=node;
        }
    }
    public void nodeInitialized(Node node) {
        m_directBlockingChecker.nodeInitialized(node);
    }
    public void nodeDestroyed(Node node) {
        m_currentBlockersCache.removeNode(node);
        m_directBlockingChecker.nodeDestroyed(node);
        if (m_firstChangedNode!=null && m_firstChangedNode.getNodeID()>=node.getNodeID())
            m_firstChangedNode=null;
        if (m_lastValidatedUnchangedNode!=null && node.getNodeID()<m_lastValidatedUnchangedNode.getNodeID())
            m_lastValidatedUnchangedNode=node;
    }
    public void modelFound() {
        //System.out.println("Found  model with " + (m_tableau.getNumberOfNodesInTableau()-m_tableau.getNumberOfMergedOrPrunedNodes()) + " nodes. ");
        if (m_blockingSignatureCache!=null) {
            // Since we've found a model, we know what is blocked or not.
            // Therefore, we don't need to update the blocking status.
            assert m_firstChangedNode==null;
            Node node=m_tableau.getFirstTableauNode();
            while (node!=null) {
                if (!node.isBlocked() && m_directBlockingChecker.canBeBlocker(node))
                    m_blockingSignatureCache.addNode(node);
                node=node.getNextTableauNode();
            }
        }
//        System.out.println("Violations for the blocker:");
//        SortedSet<ViolationStatistic> counts=new TreeSet<ViolationStatistic>();
//        for (String key : m_violationCountBlocker.keySet()) {
//            counts.add(new ViolationStatistic(key,m_violationCountBlocker.get(key)));
//        }
//        for (ViolationStatistic vs : counts) {
//            System.out.println(vs);
//        }
//        counts.clear();
//        System.out.println("Violations for the blocked parent:");
//        for (String key : m_violationCountBlockedParent.keySet()) {
//            counts.add(new ViolationStatistic(key,m_violationCountBlockedParent.get(key)));
//        }
//        for (ViolationStatistic vs : counts) {
//            System.out.println(vs);
//        }
    }
    protected final class ViolationStatistic implements Comparable<ViolationStatistic>{
        public final String m_violatedConstraint;
        public final Integer m_numberOfViolations;
        public ViolationStatistic(String violatedConstraint, Integer numberOfViolations) {
            m_violatedConstraint=violatedConstraint;
            m_numberOfViolations=numberOfViolations;
        }
        public int compareTo(ViolationStatistic that) {
            if (this==that) return 0;
            if (that==null) throw new NullPointerException("Comparing to a null object is illegal. ");
            if (this.m_numberOfViolations==that.m_numberOfViolations) return m_violatedConstraint.compareTo(that.m_violatedConstraint);
            else return that.m_numberOfViolations-this.m_numberOfViolations;
        }
        public String toString() {
            return m_numberOfViolations + ": "+m_violatedConstraint.replaceAll("http://www.co-ode.org/ontologies/galen#", "");
        }
    }
    public boolean isExact() {
        return false;
    }
    public void dlClauseBodyCompiled(List<DLClauseEvaluator.Worker> workers,DLClause dlClause,List<Variable> variables,Object[] valuesBuffer,boolean[] coreVariables) {
        if (m_useSingletonCore) {
            for (int i=0;i<coreVariables.length;i++) {
                coreVariables[i]=false;
            }
        } else {
            if (dlClause.m_clauseType!=ClauseType.CONCEPT_INCLUSION) {
                for (int i=0;i<coreVariables.length;i++) {
                    coreVariables[i]=false;
                }
                return;
            }
            if (dlClause.getHeadLength() > 2) {
                // in case of a disjunction, there is nothing to compute, the choice must go into the core
                // I assume that disjunctions are always only for the centre variable X and I assume that X is the first
                // variable in the array ???
                coreVariables[0] = true;
            } else {
                workers.add(new ComputeCoreVariables(dlClause,valuesBuffer,coreVariables));
            }
        }
    }
    protected static final class ComputeCoreVariables implements DLClauseEvaluator.Worker,Serializable {
        private static final long serialVersionUID=899293772370136783L;

        protected final DLClause m_dlClause;
        protected final Object[] m_valuesBuffer;
        protected final boolean[] m_coreVariables;

        public ComputeCoreVariables(DLClause dlClause,Object[] valuesBuffer,boolean[] coreVariables) {
            m_dlClause=dlClause;
            m_valuesBuffer=valuesBuffer;
            m_coreVariables=coreVariables;
        }
        public int execute(int programCounter) {
            if (m_dlClause.getHeadLength() > 0 && (m_dlClause.getHeadAtom(0).getArity() != 1 || !m_dlClause.getHeadAtom(0).containsVariable(Variable.create("X")))) {
                Node potentialNoncore=null;
                int potentialNoncoreIndex=-1;
                for (int variableIndex=m_coreVariables.length-1;variableIndex>=0;--variableIndex) {
                    m_coreVariables[variableIndex]=true;
                    Node node=(Node)m_valuesBuffer[variableIndex];
                    if (node.getNodeType()==NodeType.TREE_NODE && (potentialNoncore==null || node.getTreeDepth()>potentialNoncore.getTreeDepth())) {
                        potentialNoncore=node;
                        potentialNoncoreIndex=variableIndex;
                    }
                }
                if (potentialNoncore!=null) {
                    boolean isNoncore=true;
                    for (int variableIndex=m_coreVariables.length-1;isNoncore && variableIndex>=0;--variableIndex) {
                        Node node=(Node)m_valuesBuffer[variableIndex];
                        if (!node.isRootNode() && potentialNoncore!=node && potentialNoncore.isAncestorOf(node))
                            isNoncore=false;
                    }
                    if (isNoncore) {
                        m_coreVariables[potentialNoncoreIndex]=false;
                    }
                }
            }
            return programCounter+1;
        }
        // What is this doing exactly?
        // Wht do you not need the DL clase itself?
//        public int execute(int programCounter) {
//            Node potentialNoncore=null;
//            int potentialNoncoreIndex=-1;
//            for (int variableIndex=m_coreVariables.length-1;variableIndex>=0;--variableIndex) {
//                m_coreVariables[variableIndex]=true;
//                Node node=(Node)m_valuesBuffer[variableIndex];
//                if (node.getNodeType()==NodeType.TREE_NODE && (potentialNoncore==null || node.getTreeDepth()<potentialNoncore.getTreeDepth())) {
//                    potentialNoncore=node;
//                    potentialNoncoreIndex=variableIndex;
//                }
//            }
//            if (potentialNoncore!=null) {
//                boolean isNoncore=true;
//                for (int variableIndex=m_coreVariables.length-1;isNoncore && variableIndex>=0;--variableIndex) {
//                    Node node=(Node)m_valuesBuffer[variableIndex];
//                    if (!node.isRootNode() && potentialNoncore!=node && !potentialNoncore.isAncestorOf(node))
//                        isNoncore=false;
//                }
//                if (isNoncore) {
//                    m_coreVariables[potentialNoncoreIndex]=false;
//                }
//            }
//            return programCounter+1;
//        }
        public String toString() {
            return "Compute core variables";
        }
    }
}
class ValidatedBlockersCache {
    protected Tableau m_tableau;
    protected final DirectBlockingChecker m_directBlockingChecker;
    protected CacheEntry[] m_buckets;
    protected int m_numberOfElements;
    protected int m_threshold;
    protected CacheEntry m_emptyEntries;

    public ValidatedBlockersCache(DirectBlockingChecker directBlockingChecker) {
        m_directBlockingChecker=directBlockingChecker;
        clear();
    }
    public boolean isEmpty() {
        return m_numberOfElements==0;
    }
    public void clear() {
        m_buckets=new CacheEntry[1024];
        m_threshold=(int)(m_buckets.length*0.75);
        m_numberOfElements=0;
        m_emptyEntries=null;
    }
    public boolean removeNode(Node node) {
        // Check addNode() for an explanation of why we associate the entry with the node.
        ValidatedBlockersCache.CacheEntry removeEntry=(ValidatedBlockersCache.CacheEntry)node.getBlockingCargo();
        if (removeEntry!=null) {
            int bucketIndex=getIndexFor(removeEntry.m_hashCode,m_buckets.length);
            CacheEntry lastEntry=null;
            CacheEntry entry=m_buckets[bucketIndex];
            while (entry!=null) {
                if (entry==removeEntry) {
                    if (node == entry.m_nodes.get(0)) {
                        // the whole entry needs to be removed
                        for (Node n : entry.m_nodes) {
                            n.setBlockingCargo(null);
                        }
                        if (lastEntry==null)
                            m_buckets[bucketIndex]=entry.m_nextEntry;
                        else
                            lastEntry.m_nextEntry=entry.m_nextEntry;
                        entry.m_nextEntry=m_emptyEntries;
                        entry.m_nodes=new ArrayList<Node>();
                        entry.m_hashCode=0;
                        m_emptyEntries=entry;
                        m_numberOfElements--;
                    } else {
                        if (entry.m_nodes.contains(node)) {
                            for (int i=entry.m_nodes.size()-1; i>=entry.m_nodes.indexOf(node); i--) {
                                entry.m_nodes.get(i).setBlockingCargo(null);
                            }
                            entry.m_nodes.subList(entry.m_nodes.indexOf(node), entry.m_nodes.size()).clear();
                        } else {
                            throw new IllegalStateException("Internal error: entry not in cache!");
                        }
                    }
                    return true;
                }
                lastEntry=entry;
                entry=entry.m_nextEntry;
            }
            throw new IllegalStateException("Internal error: entry not in cache!");
        }
        return false;
    }
    public void addNode(Node node) {
        int hashCode=m_directBlockingChecker.blockingHashCode(node);
        int bucketIndex=getIndexFor(hashCode,m_buckets.length);
        CacheEntry entry=m_buckets[bucketIndex];
        while (entry!=null) {
            if (hashCode==entry.m_hashCode && m_directBlockingChecker.isBlockedBy(entry.m_nodes.get(0),node)) {
                if (!entry.m_nodes.contains(node)) {
                    entry.add(node);
                    node.setBlockingCargo(entry);
                    return;
                } else {
                    throw new IllegalStateException("Internal error: node already in the cache!");
                }
            }
            entry=entry.m_nextEntry;
        }
        if (m_emptyEntries==null)
            entry=new CacheEntry();
        else {
            entry=m_emptyEntries;
            m_emptyEntries=m_emptyEntries.m_nextEntry;
        }
        entry.initialize(node,hashCode,m_buckets[bucketIndex]);
        m_buckets[bucketIndex]=entry;
        // When a node is added to the cache, we record with the node the entry.
        // This is used to remove nodes from the cache. Note that changes to a node
        // can affect its label. Therefore, we CANNOT remove a node by taking its present
        // blocking hash-code, as this can be different from the hash-code used at the
        // time the node has been added to the cache.
        node.setBlockingCargo(entry);
        m_numberOfElements++;
        if (m_numberOfElements>=m_threshold)
            resize(m_buckets.length*2);
    }
    protected void resize(int newCapacity) {
        CacheEntry[] newBuckets=new CacheEntry[newCapacity];
        for (int i=0;i<m_buckets.length;i++) {
            CacheEntry entry=m_buckets[i];
            while (entry!=null) {
                CacheEntry nextEntry=entry.m_nextEntry;
                int newIndex=getIndexFor(entry.m_hashCode,newCapacity);
                entry.m_nextEntry=newBuckets[newIndex];
                newBuckets[newIndex]=entry;
                entry=nextEntry;
            }
        }
        m_buckets=newBuckets;
        m_threshold=(int)(newCapacity*0.75);
    }
    public Node getBlocker(Node node) {
        if (m_directBlockingChecker.canBeBlocked(node)) {
            int hashCode=m_directBlockingChecker.blockingHashCode(node);
            int bucketIndex=getIndexFor(hashCode,m_buckets.length);
            CacheEntry entry=m_buckets[bucketIndex];
            while (entry!=null) {
                if (hashCode==entry.m_hashCode && m_directBlockingChecker.isBlockedBy(entry.m_nodes.get(0),node)) {
                    if (node.getBlocker()!=null && entry.m_nodes.contains(node.getBlocker())) {
                        // don't change the blocker unnecessarily, the blocking validation code will change the blocker if necessary
                        return node.getBlocker();
                    } else {
                        return entry.m_nodes.get(0);
                    }
                }
                entry=entry.m_nextEntry;
            }
        }
        return null;
    }
    public List<Node> getPossibleBlockers(Node node) {
        if (m_directBlockingChecker.canBeBlocked(node)) {
            int hashCode=m_directBlockingChecker.blockingHashCode(node);
            int bucketIndex=getIndexFor(hashCode,m_buckets.length);
            CacheEntry entry=m_buckets[bucketIndex];
            while (entry!=null) {
                if (hashCode==entry.m_hashCode && m_directBlockingChecker.isBlockedBy(entry.m_nodes.get(0),node)) {
                    if (entry.m_nodes.contains(node)) { 
                        throw new IllegalStateException("Internal error: We try to block a node that is in the blockers cache. ");
                    } else {
                        return entry.m_nodes;
                    }
                }
                entry=entry.m_nextEntry;
            }
        }
        return new ArrayList<Node>();
    }
    protected static int getIndexFor(int hashCode,int tableLength) {
        hashCode+=~(hashCode << 9);
        hashCode^=(hashCode >>> 14);
        hashCode+=(hashCode << 4);
        hashCode^=(hashCode >>> 10);
        return hashCode & (tableLength-1);
    }
    public String toString() {
        String buckets = "";
        for (int i = 0; i < m_buckets.length; i++) {
            CacheEntry entry=m_buckets[i];
            if (entry != null) {
                buckets += "Bucket " + i + ": [" + entry.toString() + "] ";
            }
        }
        return buckets;
    }
    public void doSanityCheck(boolean allSingletonSets) {
        for (int i=0;i<m_buckets.length;i++) {
            CacheEntry entry=m_buckets[i];
            while (entry!=null) {
                if (allSingletonSets && entry.m_nodes.size() > 1) {
                    throw new IllegalStateException("Internal error: we expect all cache entries to be singleton sets, but the is a set with cardinality greater than one. ");
                }
                entry.doSanityCheck(m_directBlockingChecker);
                CacheEntry nextEntry=entry.m_nextEntry;
                entry=nextEntry;
            }
        }
    }
    
    public static class CacheEntry implements Serializable {
        private static final long serialVersionUID=-7047487963170250200L;

        protected List<Node> m_nodes;
        protected int m_hashCode;
        protected CacheEntry m_nextEntry;

        public void initialize(Node node,int hashCode,CacheEntry nextEntry) {
            m_nodes=new ArrayList<Node>();
            add(node);
            m_hashCode=hashCode;
            m_nextEntry=nextEntry;
        }
        public boolean add(Node node) {
            for (Node n : m_nodes) {
                if (n.getNodeID() >= node.getNodeID()) 
                    throw new IllegalStateException("Internal error: a node is added to a cache entry that is smaller than other nodes in this cache entry. ");
            }
            return m_nodes.add(node);
        }
        public String toString() {
            String nodes = "HashCode: " + m_hashCode + " Nodes: ";
            for (Node n : m_nodes) {
                nodes += n.getNodeID() + " ";
            }
            return nodes;
        }
        public void doSanityCheck(DirectBlockingChecker directBlockingChecker) {
            for (Node n : m_nodes) {
                if (m_hashCode!=directBlockingChecker.blockingHashCode(n)) 
                if (!n.isActive()) throw new IllegalStateException("Internal error: Node "+n+" is not active but in the blocking cache. ");
                if (n.isBlocked()) throw new IllegalStateException("Internal error: Node "+n+" is blocked but in the blocking cache. ");
                if (n.isMerged()) throw new IllegalStateException("Internal error: Node "+n+" is merged but in the blocking cache. ");
                if (n.isPruned()) throw new IllegalStateException("Internal error: Node "+n+" is pruned but in the blocking cache. ");
            }
        }
    }
}