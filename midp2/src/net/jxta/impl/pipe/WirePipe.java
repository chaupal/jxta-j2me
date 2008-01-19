/*
 *  Copyright (c) 2001-2008 Sun Microsystems, Inc.  All rights
 *  reserved.
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
 *  4. The names "Sun", "Sun Microsystems, Inc.", "JXTA" and "Project JXTA" must
 *  not be used to endorse or promote products derived from this
 *  software without prior written permission. For written
 *  permission, please contact Project JXTA at http://www.jxta.org.
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
 *  =========================================================
 *
 *  This software consists of voluntary contributions made by many
 *  individuals on behalf of Project JXTA.  For more
 *  information on Project JXTA, please see
 *  <http://www.jxta.org/>.
 *
 *  This license is based on the BSD license adopted by the Apache Foundation.
 *
 *  $Id: $
 */
package net.jxta.impl.pipe;

import com.sun.java.util.collections.*;
import net.jxta.document.MimeMediaType;
import net.jxta.document.StructuredDocumentFactory;
import net.jxta.document.XMLDocument;
import net.jxta.endpoint.*;
import net.jxta.id.ID;
import net.jxta.id.UUID.UUID;
import net.jxta.id.UUID.UUIDFactory;
import net.jxta.peergroup.PeerGroup;
import net.jxta.pipe.InputPipe;
import net.jxta.protocol.PipeAdvertisement;
import net.jxta.rendezvous.RendezVousService;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;

import java.io.IOException;

/**
 * This class implements the JXTA-WIRE pipe.
 */
public class WirePipe implements EndpointListener, InputPipe, PipeRegistrar {

    /**
     * Log4J Logger
     */
    private final static transient Logger LOG = Logger.getInstance(WirePipe.class.getName());

    private final static int MAX_RECORDED_MSGIDS = 250;

    private volatile boolean closed = false;
    private final String localPeerId;

    /**
     * The list of message ids we have already seen. The most recently seen
     * messages are at the end of the list. <p/>
     * <p/>
     * <p/>
     * <ul>
     * <li> Values are {@link net.jxta.impl.id.UUID.UUID}.</li>
     * </ul>
     * <p/>
     * <p/>
     * XXX 20031102 bondolo@jxta.org: We might want to consider three
     * performance enhancements:
     * <ul>
     * <li> Reverse the order of elements in the list. This would result in
     * faster searching since the default strategy for ArrayList to search in
     * index order. We are most likely to see duplicate messages amongst the
     * messages we have seen recently. This would make additions more costly.
     * </li>
     * <li> When we do find a duplicate in the list, exchange it's location
     * with the newest message in the list. This will keep annoying dupes
     * close to the start of the list.</li>
     * <li> When the array reaches MaxNbOfStoredIds treat it as a ring.</li>
     * <p/>
     * </ul>
     */
    private List msgIds = new ArrayList(MAX_RECORDED_MSGIDS);

    private PeerGroup myGroup = null;

    /**
     * Count of the number of local input pipes. Though it's mostly the same we
     * can't use <code>wireinputpipes.size()</code> because it's too twitchy.
     * We can guarntee that this field's value will change in predictable ways.
     */
    private int nbInputPipes = 0;
    private PipeAdvertisement pipeAdv = null;
    private PipeResolver pipeResolver = null;
    private WirePipeImpl pipeService = null;

    private RendezVousService rendezvous = null;
    private NonBlockingWireOutputPipe repropagater = null;

    /**
     * Table of local input pipes listening on this pipe. Weak map so that we
     * don't keep pipes unnaturally alive and consuming resources. <p/>
     * <p/>
     * <p/>
     * <ul>
     * <li> Values are {@link net.jxta.pipe.InputPipe}.</li>
     * </ul>
     */
    private Map wireinputpipes = new HashMap();

    /**
     * Constructor
     *
     * @param g            The Group associated with this service
     * @param pipeResolver the associated pipe resolver
     * @param pipeService  The pipe service associated with this pipe
     * @param adv          pipe advertisement
     */
    public WirePipe(PeerGroup g,
                    PipeResolver pipeResolver,
                    WirePipeImpl pipeService,
                    PipeAdvertisement adv) {

        this.myGroup = g;
        this.pipeResolver = pipeResolver;
        this.pipeService = pipeService;
        this.pipeAdv = adv;
        localPeerId = myGroup.getPeerID().toString();
        rendezvous = g.getRendezVousService();
        pipeResolver.register(this);
        repropagater = pipeService.createOutputPipe(adv, Collections.EMPTY_SET);
    }

    /**
     * Calls the local listeners for a given pipe
     *
     * @param message Description of the Parameter
     * @param srcAddr Description of the Parameter
     * @param dstAddr Description of the Parameter
     */
    private void callLocalListeners(Message message, EndpointAddress srcAddr, EndpointAddress dstAddr) {

        srcAddr = (null == srcAddr) ? null : EndpointAddress.unmodifiableEndpointAddress(srcAddr);
        dstAddr = (null == dstAddr) ? null : EndpointAddress.unmodifiableEndpointAddress(dstAddr);

        Iterator eachInput = wireinputpipes.keySet().iterator();

        while (eachInput.hasNext()) {
            InputPipeImpl anInputPipe = (InputPipeImpl) eachInput.next();
            Message tmpMsg = (Message) message.clone();

            try {
                anInputPipe.processIncomingMessage(tmpMsg, srcAddr, dstAddr);
            } catch (Throwable ignored) {
                if (LOG.isEnabledFor(Priority.ERROR)) {
                    LOG.error("Uncaught Throwable during callback (" + anInputPipe + ") for " + anInputPipe.getPipeID(), ignored);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void close() {
        if (closed) {
            return;
        }

        pipeResolver.forget(this);
        repropagater.close();
        closed = true;
    }

    /**
     * Create a unique (mostly) identifier for this message
     *
     * @return Description of the Return Value
     */
    String createMsgId() {
        return UUIDFactory.newSeqUUID().toString();
    }

    /**
     * {@inheritDoc} <p/>
     * <p/>
     * Closes the pipe.
     */
    protected synchronized void finalize() {

        if (!closed) {
            if (LOG.isEnabledFor(Priority.WARN)) {
                LOG.warn("Pipe is being finalized without being previously closed. This is likely a bug.");
            }
        }
        close();
    }

    /**
     * {@inheritDoc}
     *
     * @param wireinputpipe Description of the Parameter
     * @return Description of the Return Value
     */
    public synchronized boolean forget(InputPipe wireinputpipe) {
        wireinputpipes.remove(wireinputpipe);

        nbInputPipes--;
        if (0 == nbInputPipes) {
//            if (LOG.isEnabledFor(Level.INFO)) {
            if (LOG.isEnabledFor(Priority.INFO)) {
                LOG.info("Deregistering wire pipe with SRDI");
            }
            pipeResolver.pushSrdi(this, false);
            EndpointListener removed = myGroup.getEndpointService().removeIncomingMessageListener("PipeService", wireinputpipe.getPipeID().toString());

            if ((null == removed) || (this != removed)) {
                removed = myGroup.getEndpointService().removeIncomingMessageListener("PipeService", wireinputpipe.getPipeID().toString());

                if ((null == removed) || (this != removed)) {

                    if (LOG.isEnabledFor(Priority.WARN)) {
                        LOG.warn("removeIncomingMessageListener() did not remove correct pipe!");
                    }
                }
            }

            if (nbInputPipes < 0) {
                // we reset this to zero so that re-registration works.
                nbInputPipes = 0;
            }


            if (LOG.isEnabledFor(Priority.WARN)) {
                LOG.warn("Number of pipe listeners was < 0");
            }
        }

        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return The advertisement value
     */
    public PipeAdvertisement getAdvertisement() {
        return pipeAdv;
    }

    /**
     * {@inheritDoc}
     *
     * @return The name value
     */
    public String getName() {
        return pipeAdv.getName();
    }

    /**
     * {@inheritDoc}
     *
     * @return The pipeID value
     */
    public ID getPipeID() {
        return pipeAdv.getPipeID();
    }

    /**
     * {@inheritDoc}
     *
     * @return The type value
     */
    public String getType() {
        return pipeAdv.getType();
    }

    /**
     * {@inheritDoc}
     *
     * @param timeout Description of the Parameter
     * @return Description of the Return Value
     * @throws InterruptedException Description of the Exception
     */
    public Message poll(int timeout) throws InterruptedException {
        if (LOG.isEnabledFor(Priority.DEBUG)) {
            LOG.debug("This method is not really supported.");
        }

        return null;
    }

    /**
     * {@inheritDoc}
     *
     * @param message Description of the Parameter
     * @param srcAddr Description of the Parameter
     * @param dstAddr Description of the Parameter
     */
    public void processIncomingMessage(Message message, EndpointAddress srcAddr, EndpointAddress dstAddr) {

        // Check if there is a JXTA-WIRE header
        MessageElement elem = message.getMessageElement("jxta", WirePipeImpl.WireTagName);

        if (null == elem) {
            if (LOG.isEnabledFor(Priority.DEBUG)) {
                LOG.debug("No JxtaWireHeader element. Discarding " + message);
            }
            return;
        }

        WireHeader header;
        try {
            XMLDocument doc = (XMLDocument)
                    StructuredDocumentFactory.newStructuredDocument(elem.getMimeType(), elem.getStream());
            header = new WireHeader(doc);
        } catch (Exception e) {
            if (LOG.isEnabledFor(Priority.WARN)) {
                LOG.warn("bad wire header", e);
            }
            return;
        }

        processIncomingMessage(message, header, srcAddr, dstAddr);
    }

    /**
     * local version with the wire header already parsed. There are two paths
     * to this point; via the local endpoint listener or via the general
     * propagation listener in WirePipeImpl.
     *
     * @param message Description of the Parameter
     * @param header  Description of the Parameter
     * @param srcAddr Description of the Parameter
     * @param dstAddr Description of the Parameter
     */
    void processIncomingMessage(Message message, WireHeader header, EndpointAddress srcAddr, EndpointAddress dstAddr) {

        if (header.getSrcPeer().equals(localPeerId)) {
            if (LOG.isEnabledFor(Priority.DEBUG)) {
                LOG.debug("Loopback detected - discarding " + message);
            }
            return;
        }

        if (recordSeenMessage(header.getMsgId())) {
            if (LOG.isEnabledFor(Priority.DEBUG)) {
                LOG.debug("Discarding duplicate " + message);
            }
            return;
        }
        if (LOG.isEnabledFor(Priority.DEBUG)) {
            LOG.debug("Processing " + message + " on " + pipeAdv.getPipeID());
        }
        //net.jxta.impl.util.MessageUtil.printMessageStats(message,true);
        if (myGroup.isRendezvous()) {
            // local listeners are called during repropagate
            repropagate(message, header);
        } else {
            callLocalListeners(message, srcAddr, dstAddr);
        }
    }

    /**
     * Adds a message ID to our table or stored IDs.
     *
     * @param id Description of the Parameter
     * @return false if ID was added, true if it was a duplicate.
     */
    private boolean recordSeenMessage(String id) {

        UUID msgid = null;
        try {
            msgid = new UUID(id);
        } catch (IllegalArgumentException notauuid) {
            // XXX 20031024 bondolo@jxta.org these two conversions are provided
            // for backwards compatibility and should eventually be removed.
            try {
                msgid = UUIDFactory.newHashUUID(Long.parseLong(id), 0);
            } catch (NumberFormatException notanumber) {
                msgid = UUIDFactory.newHashUUID(id.hashCode(), 0);
            }
        }

        synchronized (msgIds) {
            if (msgIds.contains(msgid)) {
                // Already there. Nothing to do
                if (LOG.isEnabledFor(Priority.DEBUG)) {
                    LOG.debug("duplicate " + msgid);
                }
                return true;
            }

            if (msgIds.size() >= MAX_RECORDED_MSGIDS) {
                // The cache is full. Remove the oldest
                if (LOG.isEnabledFor(Priority.DEBUG)) {
                    LOG.debug("Remove oldest id");
                }
                msgIds.remove(0);
            }

            msgIds.add(msgid);
        }
        if (LOG.isEnabledFor(Priority.DEBUG)) {
            LOG.debug("added " + msgid);
        }

        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @param wireinputpipe Description of the Parameter
     * @return Description of the Return Value
     */
    public synchronized boolean register(InputPipe wireinputpipe) {
        wireinputpipes.put(wireinputpipe, null);
        nbInputPipes++;
        if (1 == nbInputPipes) {
            if (LOG.isEnabledFor(Priority.INFO)) {
                LOG.info("Registering wire pipe with SRDI");
            }

            boolean registered = myGroup.getEndpointService().addIncomingMessageListener((EndpointListener) wireinputpipe, "PipeService", wireinputpipe.getPipeID().toString());
            if (!registered) {
                if (LOG.isEnabledFor(Priority.WARN)) {
                    LOG.warn("Existing Registered Endpoint Listener for " + wireinputpipe.getPipeID());
                }
            }
            pipeResolver.pushSrdi(this, true);
        }

        return true;
    }

    /**
     * Repropagate a message.
     *
     * @param message Description of the Parameter
     * @param header  Description of the Parameter
     */
    void repropagate(Message message, WireHeader header) {

        Message msg = (Message) message.clone();
        XMLDocument headerDoc = (XMLDocument) header.getDocument(MimeMediaType.XMLUTF8);
        MessageElement elem = new TextDocumentMessageElement(WirePipeImpl.WireTagName, headerDoc, null);
        msg.replaceMessageElement("jxta", elem);

        if (LOG.isEnabledFor(Priority.DEBUG)) {
            LOG.debug("Repropagating " + msg + " on " + header.getPipeID());
        }

        try {
            if (!repropagater.enqueue(msg)) {
                // XXX bondolo@jxta.org we don't make any attempt to retry.
                // There is a potential problem in that we have accepted the
                // message locally but didn't resend it. If we get another copy
                // of this message then we will NOT attempt to repropagate it
                // even if we should.
                if (LOG.isEnabledFor(Priority.WARN)) {
                    LOG.warn("Failure repropagating " + msg + " on " + header.getPipeID() + ". Could not queue message.");
                }
            }
        } catch (IOException failed) {
            if (LOG.isEnabledFor(Priority.WARN)) {
                LOG.warn("Failure repropagating " + msg + " on " + header.getPipeID(), failed);
            }
        }
    }

    /**
     * Send the message
     *
     * @param msg   The message to send.
     * @param peers The peers to which the message will be sent. If
     *              the set is empty then the message is sent to all connected peers or
     *              via propagation to the rendezvous.
     * @throws IOException Description of the Exception
     */
    void sendMessage(Message msg, Set peers) throws IOException {

        // do local listeners if we are to be one of the destinations
        if (peers.isEmpty() || peers.contains(myGroup.getPeerID())) {
            callLocalListeners(msg, null, null);
        }

        if (peers.isEmpty()) {
            if (LOG.isEnabledFor(Priority.DEBUG)) {
                LOG.debug("Propagating " + msg + " to whole network.");
            }

            // We know nothing. Propagate the message globally. The TTL will be reduced appropriately by reforwarders.
            rendezvous.walk(msg, WirePipeImpl.WireName, pipeService.getServiceParameter(), RendezVousService.DEFAULT_TTL);
        } else {
            // Send to specific peers
            if (LOG.isEnabledFor(Priority.DEBUG)) {
                LOG.debug("Propagating " + msg + " to " + peers.size() + " peers.");
            }

            rendezvous.propagate(Collections.enumeration(peers), msg, WirePipeImpl.WireName, pipeService.getServiceParameter(), 1);
        }
    }

    // This is the InputPipe API implementation.
    // This is needed only to be able to register an InputPipe to the PipeResolver. Not everything
    // has to be implemented.

    /**
     * {@inheritDoc}
     */
    public Message waitForMessage() throws InterruptedException {
        if (LOG.isEnabledFor(Priority.DEBUG)) {
            LOG.debug("This method is not really supported.");
        }
        return null;
    }
}