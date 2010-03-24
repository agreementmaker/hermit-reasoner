/* Copyright 2008, 2009, 2010 by the Oxford University Computing Laboratory

   This file is part of HermiT.

   HermiT is free software: you can redistribute it and/or modify
   it under the terms of the GNU Lesser General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   HermiT is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU Lesser General Public License for more details.

   You should have received a copy of the GNU Lesser General Public License
   along with HermiT.  If not, see <http://www.gnu.org/licenses/>.
*/
package org.semanticweb.HermiT.tableau;

import org.semanticweb.HermiT.Prefixes;
import org.semanticweb.HermiT.model.AtLeastConcept;
import org.semanticweb.HermiT.model.DLPredicate;

/**
 * This class is used to create sets of various types. It ensures that each distinct set exists only once,
 * thus allowing sets to be compared with ==.
 */
public final class Disjunction {
    protected final DLPredicate[] m_dlPredicates;
    protected final int m_hashCode;
    protected final DisjunctIndexWithBacktrackings[] m_disjunctIndexesWithBacktrackings;
    protected final int m_firstAtLeastIndex;
    protected Disjunction m_nextEntry;

    protected Disjunction(DLPredicate[] dlPredicates,int hashCode,Disjunction nextEntry) {
        m_dlPredicates=dlPredicates;
        m_hashCode=hashCode;
        m_nextEntry=nextEntry;
        m_disjunctIndexesWithBacktrackings=new DisjunctIndexWithBacktrackings[dlPredicates.length];
        // We want that all existential concepts come after the atomics: experience shows
        // that it is usually better to try atomics first. Therefore, we initialize
        // m_disjunctIndexesWithBacktrackings such that atomic disjuncts are placed first.
        // Later on we will ensure that the disjuncts always remain in that order.
        int numberOfAtLeastDisjuncts=0;
        for (int index=0;index<dlPredicates.length;index++)
            if (m_dlPredicates[index] instanceof AtLeastConcept)
                numberOfAtLeastDisjuncts++;
        m_firstAtLeastIndex=m_disjunctIndexesWithBacktrackings.length-numberOfAtLeastDisjuncts;
        int nextNonAtLeastDisjunct=0;
        int nextAtLeastDisjunct=m_firstAtLeastIndex;
        for (int index=0;index<dlPredicates.length;index++)
            if (m_dlPredicates[index] instanceof AtLeastConcept)
                m_disjunctIndexesWithBacktrackings[nextAtLeastDisjunct++]=new DisjunctIndexWithBacktrackings(index);
            else
                m_disjunctIndexesWithBacktrackings[nextNonAtLeastDisjunct++]=new DisjunctIndexWithBacktrackings(index);
    }
    public DLPredicate[] getDisjuncts() {
        return m_dlPredicates;
    }
    public int getNumberOfDisjuncts() {
        return m_dlPredicates.length;
    }
    public DLPredicate getDisjunct(int disjunctIndex) {
        return m_dlPredicates[disjunctIndex];
    }
    public boolean isEqual(DLPredicate[] dlPredicates) {
        if (m_dlPredicates.length!=dlPredicates.length)
            return false;
        for (int index=m_dlPredicates.length-1;index>=0;--index)
            if (!m_dlPredicates[index].equals(dlPredicates[index]))
                return false;
        return true;
    }
    public int[] getSortedDisjunctIndexes() {
        int[] sortedDisjunctIndexes=new int[m_disjunctIndexesWithBacktrackings.length];
        for (int index=m_disjunctIndexesWithBacktrackings.length-1;index>=0;--index)
            sortedDisjunctIndexes[index]=m_disjunctIndexesWithBacktrackings[index].m_disjunctIndex;
        return sortedDisjunctIndexes;
    }
    public void increaseNumberOfBacktrackings(int disjunctIndex) {
        if (!m_disjunctIndexesWithBacktrackings[0].m_stopSwapping) {
            for (int index=0;index<m_disjunctIndexesWithBacktrackings.length;index++) {
                DisjunctIndexWithBacktrackings disjunctIndexWithBacktrackings=m_disjunctIndexesWithBacktrackings[index];
                if (disjunctIndexWithBacktrackings.m_beenBestChoice && disjunctIndexWithBacktrackings.m_beenWorstChoice) {
                    // freeze - stopSwapping
                    m_disjunctIndexesWithBacktrackings[0].m_stopSwapping=true;
                    Prefixes prefixes=new Prefixes();
                    prefixes.declarePrefix("", "http://oiled.man.example.net/test#");
                    prefixes.declareSemanticWebPrefixes();
                    System.out.println("Stopped swapping for: "+this.toString(prefixes));
                    break;
                }
                if (disjunctIndexWithBacktrackings.m_disjunctIndex==disjunctIndex) {
                    disjunctIndexWithBacktrackings.m_numberOfBacktrackings++;
                    int partitionStart=(index<m_firstAtLeastIndex ? 0 : m_firstAtLeastIndex);
                    int partitionEnd=(index<m_firstAtLeastIndex ? m_firstAtLeastIndex : m_disjunctIndexesWithBacktrackings.length);
                    int currentIndex=index;
                    int nextIndex=currentIndex+1;
                    while (nextIndex<partitionEnd && disjunctIndexWithBacktrackings.m_numberOfBacktrackings>m_disjunctIndexesWithBacktrackings[nextIndex].m_numberOfBacktrackings) {
                        m_disjunctIndexesWithBacktrackings[currentIndex]=m_disjunctIndexesWithBacktrackings[nextIndex];
                        m_disjunctIndexesWithBacktrackings[nextIndex]=disjunctIndexWithBacktrackings;
                        if (currentIndex==partitionStart)
                            m_disjunctIndexesWithBacktrackings[currentIndex].m_beenBestChoice=true;
                        currentIndex=nextIndex;
                        nextIndex++;
                    }
                    if (nextIndex==partitionEnd) 
                        m_disjunctIndexesWithBacktrackings[currentIndex].m_beenWorstChoice=true;
                    break;
                }
            }
        }
        
//        Set<String> disjuntStrings=new HashSet<String>();
//        disjuntStrings.add("<http://oiled.man.example.net/test#C4>");
//        disjuntStrings.add("<http://oiled.man.example.net/test#C32>");
//        disjuntStrings.add("<http://oiled.man.example.net/test#C2>");
//        if (m_dlPredicates.length==3 && disjuntStrings.contains(m_dlPredicates[0].toString()) && disjuntStrings.contains(m_dlPredicates[1].toString()) && disjuntStrings.contains(m_dlPredicates[2].toString())) {
//            Prefixes prefixes=new Prefixes();
//            prefixes.declarePrefix("", "http://oiled.man.example.net/test#");
//            prefixes.declareSemanticWebPrefixes();
//            try {
//                for (int i=0;i<m_disjunctIndexesWithBacktrackings.length;i++) {
//                    if (i > 0) System.out.print(" \\/ ");
//                    String disjunctStr=m_dlPredicates[m_disjunctIndexesWithBacktrackings[i].m_disjunctIndex].toString(prefixes);
//                    if (disjunctStr.equals(":C4")) {
//                        System.err.print(disjunctStr+" ("+m_disjunctIndexesWithBacktrackings[i].m_numberOfBacktrackings+")");
//                        System.err.flush();
//                        Thread.sleep(200);
//                    } else {
//                        System.out.print(disjunctStr+" ("+m_disjunctIndexesWithBacktrackings[i].m_numberOfBacktrackings+")");
//                        System.out.flush();
//                        Thread.sleep(200);
//                    }
//                }
//                System.out.println();
//                System.out.flush();
//                Thread.sleep(200);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//        }
    }
    public String toString(Prefixes prefixes) {
        StringBuffer buffer=new StringBuffer();
        for (int disjunctIndex=0;disjunctIndex<m_dlPredicates.length;disjunctIndex++) {
        	if (disjunctIndex>0)
        	    buffer.append(" \\/ ");
    	    buffer.append(m_dlPredicates[disjunctIndex].toString(prefixes));
    	    buffer.append(" (");
            for (DisjunctIndexWithBacktrackings disjunctIndexWithBacktrackings : m_disjunctIndexesWithBacktrackings) {
            	if (disjunctIndexWithBacktrackings.m_disjunctIndex==disjunctIndex) {
            	    buffer.append(disjunctIndexWithBacktrackings.m_numberOfBacktrackings);
            		break;
            	}
            }
            buffer.append(")");
        }
        return buffer.toString();
    }
    public String toString() {
        return toString(Prefixes.STANDARD_PREFIXES);
    }

    protected static class DisjunctIndexWithBacktrackings {
        protected boolean m_beenBestChoice;
        protected boolean m_beenWorstChoice;
        protected final int m_disjunctIndex;
        protected int m_numberOfBacktrackings;
        protected boolean m_stopSwapping;

        public DisjunctIndexWithBacktrackings(int index) {
            m_disjunctIndex=index;
        }
    }
}
