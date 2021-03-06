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

import com.sun.java.util.collections.HashSet;
import com.sun.java.util.collections.Set;
import net.jxta.document.MimeMediaType;
import net.jxta.document.XMLDocument;
import net.jxta.endpoint.Message;
import net.jxta.endpoint.MessageElement;
import net.jxta.endpoint.TextDocumentMessageElement;
import net.jxta.id.ID;
import net.jxta.impl.util.TimeUtils;
import net.jxta.impl.util.UnbiasedQueue;
import net.jxta.peergroup.PeerGroup;
import net.jxta.pipe.OutputPipe;
import net.jxta.protocol.PipeAdvertisement;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;

import java.io.IOException;

/**
 * An implementation of Ouput Pipe which sends messages on the pipe
 * asynchronously. The <code>send()</code> method for this implementation will
 * never block.
 */
class NonBlockingWireOutputPipe implements OutputPipe, Runnable {

    /**
     * Amount of time an idle worker thread will linger
     */
    private final static long IDLEWORKERLINGER = 10 * TimeUtils.ASECOND;

    /**
     * Log4J Logger
     */
    private final static Logger LOG = Logger.getInstance(NonBlockingWireOutputPipe.class.getName());

    /**
     * If true then the pipe has been closed and will no longer accept
     * messages.
     */
    private volatile boolean closed = false;

    /**
     * The set of peers to which messages on this pipe are sent. If empty then
     * the message is sent to all propagation targets.
     */
    private Set destPeers = null;

    /**
     * Group in which we are working.
     */
    private PeerGroup myGroup = null;

    /**
     * The advertisement we were created from.
     */
    private PipeAdvertisement pAdv = null;

    /**
     * Queue of messages waiting to be sent.
     */
    private UnbiasedQueue queue = UnbiasedQueue.synchronizedQueue(new UnbiasedQueue(50, false));

    /**
     * The worker thread which actually sends messages on the pipe
     */
    private volatile Thread serviceThread = null;

    /**
     * The endpoint of our group.
     */
    private WirePipe wire = null;

    /**
     * The current state of the worker thread
     */
    private workerState workerstate;

    /**
     * Create a new output pipe
     *
     * @param g     peergroup we are working in.
     * @param pAdv  advertisement for the pipe we are supporting.
     * @param peers the set of peers we allow this pipe to be bound to.
     * @param wire  Description of the Parameter
     */
    public NonBlockingWireOutputPipe(PeerGroup g, WirePipe wire, PipeAdvertisement pAdv, Set peers) {

        myGroup = g;
        this.wire = wire;
        this.destPeers = new HashSet(peers);
        this.pAdv = pAdv;
        if (LOG.isEnabledFor(Priority.INFO)) {
            LOG.info("Constructing for " + getPipeID());
        }

        workerstate = workerState.SENDMESSAGES;
    }

    /**
     * {@inheritDoc}
     */
    public synchronized void close() {

        // Close the queue so that no more messages are accepted
        if (!closed) {
            if (LOG.isEnabledFor(Priority.INFO)) {
                LOG.info("Closing queue for " + getPipeID());
            }
            queue.close();
        }
        closed = true;
    }

    /**
     * Push a message on the send queue. The message should already contain the
     * wire header.
     *
     * @param message the message to enqueue.
     * @return Description of the Return Value
     * @throws java.io.IOException for failures in queuing.
     */
    boolean enqueue(Message message) throws IOException {
        if (LOG.isEnabledFor(Priority.DEBUG)) {
            LOG.debug("Queuing " + message + " for pipe " + getPipeID());
        }

        boolean pushed = false;
        while (!queue.isClosed()) {
            try {
                pushed = queue.push(message, 250 * TimeUtils.AMILLISECOND);
                break;
            } catch (InterruptedException woken) {
            }
        }

        if (!pushed && queue.isClosed()) {
            IOException failed = new IOException("Could not enqueue " + message + " for sending. Pipe is closed.");
            if (LOG.isEnabledFor(Priority.ERROR)) {
                LOG.error(failed, failed);
            }

            throw failed;
        }
        startServiceThread();
        return pushed;
    }

    /**
     * {@inheritDoc}
     */
    protected void finalize() {
        close();
    }

    /**
     * {@inheritDoc}
     *
     * @return The advertisement value
     */
    public final PipeAdvertisement getAdvertisement() {
        return pAdv;
    }


    /**
     * {@inheritDoc}
     *
     * @return The name value
     */
    public final String getName() {
        return pAdv.getName();
    }


    /**
     * {@inheritDoc}
     *
     * @return The pipeID value
     */
    public final ID getPipeID() {
        return pAdv.getPipeID();
    }

    /**
     * {@inheritDoc}
     *
     * @return The type value
     */
    public final String getType() {
        return pAdv.getType();
    }

    /**
     * {@inheritDoc}
     *
     * @return The closed value
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * {@inheritDoc} <p/>
     * <p/>
     * Sends the messages. <p>
     * <p/>
     * This method does a lot of things. It has several distinct states: <p/>
     * <p/>
     * <p/>
     * <tableborder="1">
     * <p/>
     * <thead>
     * <p/>
     * <tr>
     * <p/>
     * <th>
     * STATE
     * </th>
     * <p/>
     * <th>
     * Activity
     * </th>
     * <p/>
     * <tr>
     * <p/>
     * </thead>
     * <p/>
     * <tr>
     * <p/>
     * <th>
     * SENDMESSAGES
     * </th>
     * <p/>
     * <td>
     * Send messages until queue is closed and all messages have been
     * sent. Go to state <b>CLOSED</b> when done. If the messenger
     * becomes closed then go to <b>ACQUIREMESSENGER</b> . <emphasis>
     * If there are no messages to send for <code>IDLEWORKERLINGER</code>
     * millisecondsthen the worker thread will exit. It will only be
     * restarted if another message is eventually enqueued.
     * </emphasis>
     * </td>
     * <p/>
     * </tr>
     * <p/>
     * <tr>
     * <p/>
     * <th>
     * CLOSED
     * </th>
     * <p/>
     * <td>
     * Exit the worker thread.
     * </td>
     * <p/>
     * </tr>
     * </tbody>
     * </table>
     */
    public void run() {

        try {
            // state loop
            while (workerState.CLOSED != workerstate) {
                synchronized (this) {
                    if (LOG.isEnabledFor(Priority.DEBUG)) {
                        LOG.debug("NON-BLOCKING WORKER AT STATE : " + workerstate);
                    }

                    // switch() emulation

                    if (workerState.SENDMESSAGES == workerstate) {
                        // move on to the next state.
                    } else if (workerState.CLOSED == workerstate) {
                        queue.clear();
                        // they aren't going to be sent
                        serviceThread = null;
                        break;
                    } else {
                        if (LOG.isEnabledFor(Priority.WARN)) {
                            LOG.warn("Unrecognized state in worker thread : " + workerstate);
                        }
                    }
                }

                // now actually send messages. We don't do this under the global sync.
                if (workerState.SENDMESSAGES == workerstate) {
                    Message msg;

                    try {
                        msg = (Message) queue.pop(IDLEWORKERLINGER);
                    } catch (InterruptedException woken) {
                        continue;
                    }

                    if (null == msg) {
                        synchronized (this) {
                            // before deciding to die, we need to make sure that
                            // nobody snuck something into the queue. If there
                            // is, then we have to be the one to service the
                            // queue.
                            if (null == queue.peek()) {
                                if (closed) {
                                    workerstate = workerState.CLOSED;
                                    continue;
                                } else {
                                    serviceThread = null;
                                    break;
                                }
                            } else {
                                continue;
                            }
                        }
                    }
                    if (LOG.isEnabledFor(Priority.DEBUG)) {
                        LOG.debug("Sending " + msg + " on " + getPipeID());
                    }

                    try {
                        wire.sendMessage(msg, destPeers);
                    } catch (IOException failed) {
                        if (LOG.isEnabledFor(Priority.ERROR)) {
                            LOG.error("Failed sending " + msg + " on " + getPipeID(), failed);
                        }
                    }
                }
            }
        } catch (Throwable all) {
            if (LOG.isEnabledFor(Priority.ERROR)) {
                LOG.error("Uncaught Throwable in thread :" + Thread.currentThread().getName(), all);
            }

            // give another thread the chance to start unless one already has.
            // If the exception was caused by damaged state on this object then
            // starting a new Thread may just cause the same exception again.
            // Unfortunate tradeoff.
            synchronized (this) {
                if (serviceThread == Thread.currentThread()) {
                    serviceThread = null;
                }
            }
        } finally {
            if (LOG.isEnabledFor(Priority.INFO)) {
                LOG.info("Thread exit : " + Thread.currentThread().getName() +
                        "\n\tworker state : " + workerstate +
                        "\tqueue closed : " + queue.isClosed() +
                        "\tnumber in queue : " + queue.getCurrentInQueue() +
                        "\tnumber queued : " + queue.getNumEnqueued() +
                        "\tnumber dequeued : " + queue.getNumDequeued());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public boolean send(Message message) throws IOException {

        WireHeader header = new WireHeader();
        header.setPipeID(getPipeID());
        header.setMsgId(wire.createMsgId());
        header.setTTL(destPeers.isEmpty() ? 200 : 1);
        header.addPeer(myGroup.getPeerID().toString());
        XMLDocument asDoc = (XMLDocument) header.getDocument(MimeMediaType.XMLUTF8);
        MessageElement elem = new TextDocumentMessageElement(WirePipeImpl.WireTagName, asDoc, null);
        Message msg = (Message) message.clone();
        msg.replaceMessageElement("jxta", elem);
        return enqueue(msg);
    }

    /**
     * Starts the worker thread if it is not already running.
     */
    private synchronized void startServiceThread() {
        // if there is no service thread, start one.
        if ((null == serviceThread) && !closed) {
            serviceThread = new Thread(this, "Worker Thread for NonBlockingWireOutputPipe : " + getPipeID());
            serviceThread.start();
            if (LOG.isEnabledFor(Priority.INFO)) {
                LOG.info("Thread start : " + serviceThread.getName() +
                        "\n\tworker state : " + workerstate +
                        "\tqueue closed : " + queue.isClosed() +
                        "\tnumber in queue : " + queue.getCurrentInQueue() +
                        "\tnumber queued : " + queue.getNumEnqueued() +
                        "\tnumber dequeued : " + queue.getNumDequeued());
            }
        }
    }

    /**
     * Tracks the state of our worker thread.
     */
    static class workerState {

        /**
         * Exit.
         */
        public final static workerState CLOSED =
                new workerState() {
                    public String toString() {
                        return "CLOSED";
                    }
                };

        /**
         * Send messages via the messenger to the destination peer.
         */
        public final static workerState SENDMESSAGES =
                new workerState() {
                    public String toString() {
                        return "SENDMESSAGES";
                    }
                };

        /**
         * Private Constructor. This class is only constants.
         */
        private workerState() {
        }
    }
}
