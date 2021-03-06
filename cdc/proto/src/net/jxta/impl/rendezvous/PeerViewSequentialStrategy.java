/*
 *  Copyright (c) 2002-2004 Sun Microsystems, Inc.  All rights reserved.
 *
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions
 *  are met:
 *
 *  1. Redistributions of source code must retain the above copyright
 *  notice, this list of conditions and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright
 *  notice, this list of conditions and the following disclaimer in
 *  the documentation and/or other materials provided with the
 *  distribution.
 *
 *  3. The end-user documentation included with the redistribution,
 *  if any, must include the following acknowledgment:
 *  "This product includes software developed by the
 *  Sun Microsystems, Inc. for Project JXTA."
 *  Alternately, this acknowledgment may appear in the software itself,
 *  if and wherever such third-party acknowledgments normally appear.
 *
 *  4. The names "Sun", "Sun Microsystems, Inc.", "JXTA" and
 *  "Project JXTA" must not be used to endorse or promote products
 *  derived from this software without prior written permission.
 *  For written permission, please contact Project JXTA at
 *  http://www.jxta.org.
 *
 *  5. Products derived from this software may not be called "JXTA",
 *  nor may "JXTA" appear in their name, without prior written
 *  permission of Sun.
 *
 *  THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 *  WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 *  DISCLAIMED.  IN NO EVENT SHALL SUN MICROSYSTEMS OR
 *  ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 *  SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 *  LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 *  USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 *  ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 *  OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 *  OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 *  SUCH DAMAGE.
 *  ====================================================================
 *
 *  This software consists of voluntary contributions made by many
 *  individuals on behalf of Project JXTA.  For more
 *  information on Project JXTA, please see
 *  <http://www.jxta.org/>.
 *
 *  This license is based on the BSD license adopted by the Apache
 *  Foundation.
 *
 *  $Id: PeerViewSequentialStrategy.java,v 1.1 2005/05/15 19:18:37 hamada Exp $
 */
package net.jxta.impl.rendezvous;

import java.util.SortedSet;
import java.util.Iterator;

/**
 *  Sequential
 */
class PeerViewSequentialStrategy implements PeerViewStrategy {
    private PeerViewElement current;

    private SortedSet set;

    /**
     *  Constructor for the PeerViewSequentialStrategy object
     *
     *@param  aset  Description of the Parameter
     */
    PeerViewSequentialStrategy(SortedSet aset) {
        set = aset;
        reset();
    }

    /**
     *  {@inheritDoc}
     *
     *@return    Description of the Return Value
     */
    public PeerViewElement next() {

        synchronized (set) {
            do {
                if (null == current) {
                    if (set.size() > 0) {
                        // no current, take the first
                        current = (PeerViewElement) set.first();
                        break;
                    } else {
                        // no first, return null
                        break;
                    }
                } else {
                    SortedSet tail = set.tailSet(current);

                    Iterator fromTail = tail.iterator();

                    if (fromTail.hasNext()) {
                        Comparable tailFirst = (Comparable) fromTail.next();

                        if (0 == current.compareTo(tailFirst)) {
                            if (fromTail.hasNext()) {
                                // first in tail is current so the new current
                                // is second in tail.
                                current = (PeerViewElement) fromTail.next();
                                break;
                            } else {
                                // none left in tail after current, start over.
                                current = null;
                                continue;
                            }
                        } else {
                            // current is not in the tail set, so first in tail
                            // is new current
                            current = (PeerViewElement) tailFirst;
                            break;
                        }
                    } else {
                        // none in tail, start over.
                        current = null;
                        continue;
                    }
                }
            } while (null == current);
        }
        return current;
    }


    /**
     *  {@inheritDoc}
     */
    public void reset() {
        current = null;
    }
}

