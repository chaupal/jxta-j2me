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
package net.jxta.impl.endpoint.router;

import com.sun.java.util.collections.ArrayList;
import com.sun.java.util.collections.Iterator;
import com.sun.java.util.collections.List;
import com.sun.java.util.collections.Map;
import net.jxta.document.AdvertisementFactory;
import net.jxta.endpoint.EndpointAddress;
import net.jxta.endpoint.Messenger;
import net.jxta.id.ID;
import net.jxta.peer.PeerID;
import net.jxta.protocol.AccessPointAdvertisement;
import net.jxta.protocol.RouteAdvertisement;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;

import java.util.Vector;

public class RouteControl {

    /**
     * Log4j Category
     */
    private static transient final Logger LOG = Logger.getInstance(RouteControl.class.getName());

    /**
     * return value for operation
     */
    public final static int OK = 0; // operation succeeded
    public final static int ALREADY_EXIST = 1; // failed route already exists
    public final static int FAILED = -1; // failed operation
    public final static int DIRECT_ROUTE = 2; // failed direct route
    public final static int INVALID_ROUTE = 3; // invalid route

    /**
     * Endpoint Router pointer
     */
    private EndpointRouter router = null;

    /**
     * local Peer Id
     */
    private ID localPeerId = null;

    /**
     * initialize RouteControl
     */
    public RouteControl(EndpointRouter router, ID pid) {
        this.router = router;
        this.localPeerId = pid;
    }

    /**
     * get my local route
     *
     * @return RoutAdvertisement of the local route
     */
    public RouteAdvertisement getMyLocalRoute() {
        return (RouteAdvertisement) router.getMyLocalRoute();
    }

    /**
     * add a new route. For the route to be usefull, we actively try
     * the route by trying it
     *
     * @param newRoute route to add
     * @return Integer status (OK, FAILED, INVALID_ROUTE or ALREADY_EXIST)
     */
    public int addRoute(RouteAdvertisement newRoute) {
        RouteAdvertisement route = newRoute;

        // check if the destination is not ourself
        if (route.getDestPeerID().equals(localPeerId)) {
            return ALREADY_EXIST;
        }

        AccessPointAdvertisement firstHop = route.getFirstHop();
        PeerID firstHopPid = null;
        EndpointAddress firstHopAddr = null;

        if (firstHop != null) { // The route is not necessarily a direct route.

            firstHopPid = firstHop.getPeerID();

            // The first hop is ourselves. Remove it a move to the new first hop if any.
            if (localPeerId.equals(firstHopPid)) {
                route.removeHop(firstHopPid);
                firstHop = route.getFirstHop();
            }
        }

        if (firstHop == null) { // It really is a direct route.

            // we only need to publish this route if
            // we don't know about it yet.
            EndpointAddress da = router.pid2addr(route.getDestPeerID());

            if (router.isLocalRoute(da) || router.isRoutedRoute(da)) {
                return ALREADY_EXIST;
            }

            // ok go ahead try to connect to the destination
            // using the route info
            if (router.ensureLocalRoute(da, route) == null) {
                return FAILED;
            }
            return OK;
        }

        // we have a long route

        // Manufacture a RA just that as just the routerPeer as a destination.
        // We only need to publish this route if we don't know about it yet.

        RouteAdvertisement firstHopRoute = null;

        firstHopPid = firstHop.getPeerID();
        firstHopAddr = router.pid2addr(firstHopPid);

        if (!(router.isLocalRoute(firstHopAddr) || router.isRoutedRoute(firstHopAddr))) {

            firstHopRoute = (RouteAdvertisement) AdvertisementFactory.newAdvertisement(RouteAdvertisement.getAdvertisementType());
            firstHopRoute.setDest(firstHop);
            router.updateRouteAdv(firstHopRoute);
        }

        if (router.ensureLocalRoute(firstHopAddr, firstHopRoute) == null) {
            // could not find a route to the first hop, discard the route
            return FAILED;
        }
        router.setRoute(route, false);
        return OK;
    }

    /**
     * get a current route info
     *
     * @param pId destination of the route
     * @return RouteAdvertisement current route info
     */
    public RouteAdvertisement getRouteInfo(PeerID pId) {

        RouteAdvertisement route = null;
        EndpointRouter.ClearPendingQuery entry = null;
        EndpointAddress addr = router.pid2addr(pId);

        // check if we have a direct route
        Messenger oneOfThem = router.getCachedMessenger(addr);
        EndpointAddress pcaddr = (oneOfThem == null) ? null : oneOfThem.getDestinationAddress();

        if (pcaddr != null) {

            AccessPointAdvertisement ap = (AccessPointAdvertisement)
                    AdvertisementFactory.newAdvertisement(AccessPointAdvertisement.getAdvertisementType());

            ap.setPeerID(pId);
            List eas = new ArrayList();

            eas.add(pcaddr.getProtocolName() + "://" + pcaddr.getProtocolAddress());
            ap.setEndpointAddresses(eas);
            route = (RouteAdvertisement)
                    AdvertisementFactory.newAdvertisement(RouteAdvertisement.getAdvertisementType());
            route.setDest(ap);
            return route;

        } else { // check if we have a longue route
            route = router.getRoute(addr, false);
            if (route != null) {
                return route;
            } else { // check if we have a pending query
                entry = router.getPendingRouteQuery(addr);
                if (entry != null) { // ok we have a pending query
                    AccessPointAdvertisement ap = (AccessPointAdvertisement)
                            AdvertisementFactory.newAdvertisement(AccessPointAdvertisement.getAdvertisementType());

                    ap.setPeerID(pId);
                    List eas = new ArrayList();

                    eas.add("pending " + (entry.isFailed() ? "(failed)" : "(new)"));
                    ap.setEndpointAddresses(eas);
                    route = (RouteAdvertisement)
                            AdvertisementFactory.newAdvertisement(RouteAdvertisement.getAdvertisementType());
                    route.setDest(ap);
                    return route;
                } else { // sorry no route found
                    AccessPointAdvertisement ap = (AccessPointAdvertisement)
                            AdvertisementFactory.newAdvertisement(AccessPointAdvertisement.getAdvertisementType());

                    ap.setPeerID(pId);
                    List eas = new ArrayList();

                    eas.add("unknown");
                    route = (RouteAdvertisement)
                            AdvertisementFactory.newAdvertisement(RouteAdvertisement.getAdvertisementType());
                    route.setDest(ap);
                    return route;
                }
            }
        }
    }

    /**
     * Delete route info
     *
     * @param pId destination route to be removed
     * @return Integer status
     */
    public int deleteRoute(PeerID pId) {

        // check if the route Id is not ourself
        if (pId.equals(localPeerId)) {
            return INVALID_ROUTE;
        }

        EndpointAddress addr = router.pid2addr(pId);

        // FIXME tra 20030820 We are only allowing to remove longue routes.
        // Since direct routes can be used as the first hop for multiple
        // valid routes, we don't want to close the associate messenger. At some
        // point we should introduce a way to disassociate direct routes and
        // their corresponding messengers, so we can have a generic table of routes
        // and a separated table of messengers that can be manipulated
        // independently.

        // Check if we have a direct route
        if (router.isLocalRoute(addr)) {
            return DIRECT_ROUTE;
        }

        // remove routing table info
        router.removeRoute(addr);

        return OK;
    }

    /**
     * get all the know routes by the router. Return a vector of all
     * the routes known.
     * This method which is meant for informational purposes, does not lock the maps that
     * it browses. As a result, it could in some cases generate a concurrent modification
     * exception.
     *
     * @return vector of known routes
     */
    public Vector getAllRoutesInfo() {

        Vector routes = new Vector();
        EndpointAddress ea = null;

        try {
            // get the direct routes
            for (Iterator it = router.getAllCachedMessengerDestinations(); it.hasNext();) {
                ea = (EndpointAddress) it.next();
                AccessPointAdvertisement ap = (AccessPointAdvertisement)
                        AdvertisementFactory.newAdvertisement(AccessPointAdvertisement.getAdvertisementType());

                ap.setPeerID(router.addr2pid(ea));
                List eas = new ArrayList();
                Messenger oneOfThem = router.getCachedMessenger(ea);
                EndpointAddress pcaddr = (oneOfThem == null) ? null : oneOfThem.getDestinationAddress();

                if (pcaddr == null) { // incomplete route
                    eas.add("unknown");
                } else {
                    eas.add(pcaddr.getProtocolName() + "://" + pcaddr.getProtocolAddress());
                }
                ap.setEndpointAddresses(eas);
                RouteAdvertisement r = (RouteAdvertisement)
                        AdvertisementFactory.newAdvertisement(RouteAdvertisement.getAdvertisementType());

                r.setDest(ap);

                routes.addElement(r);
            }

            // now get the long routes
            // Use entrySet, there's no point in doing redundant lookups
            // in the map.
            for (Iterator i = router.getRoutedRouteAllDestinations(); i.hasNext();) {
                Map.Entry entry = (Map.Entry) i.next();
                routes.addElement((RouteAdvertisement) entry.getValue());
            }

            for (Iterator it = router.getPendingQueriesAllDestinations(); it.hasNext();) {
                Map.Entry entry = (Map.Entry) it.next();

                ea = (EndpointAddress) entry.getKey();
                AccessPointAdvertisement ap = (AccessPointAdvertisement)
                        AdvertisementFactory.newAdvertisement(AccessPointAdvertisement.getAdvertisementType());

                ap.setPeerID(router.addr2pid(ea));
                List eas = new ArrayList();

                eas.add("pending " + (((EndpointRouter.ClearPendingQuery) entry.getValue()).isFailed() ? "(failed)" : "(new)"));
                ap.setEndpointAddresses(eas);
                RouteAdvertisement r = (RouteAdvertisement)
                        AdvertisementFactory.newAdvertisement(RouteAdvertisement.getAdvertisementType());

                r.setDest(ap);
                routes.addElement(r);
            }
        } catch (Exception ex) {
            if (LOG.isEnabledFor(Priority.WARN)) {
                LOG.warn("getAllRoutesInfo error : ", ex);
            }
        }
        return routes;
    }

    /**
     * get RouteResolver usage
     */
    public boolean useRouteResolver() {
        // FIXME hamada enable when resolver is added
        //return router.getRouteResolver().useRouteResolver();
        return false;
    }

    /**
     * enable usage of Route Resolver
     */
    public void enableRouteResolver() {
        // FIXME hamada enable when resolver is added
        //router.getRouteResolver().enableRouteResolver();
    }

    /**
     * disable usage of Route resolver
     */
    public void disableRouteResolver() {
        // FIXME hamada enable when resolver is added
        // router.getRouteResolver().disableRouteResolver();
    }
}
