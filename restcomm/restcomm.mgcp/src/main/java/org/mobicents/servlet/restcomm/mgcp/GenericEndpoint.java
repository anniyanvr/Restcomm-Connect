/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */
package org.mobicents.servlet.restcomm.mgcp;

import jain.protocol.ip.mgcp.JainMgcpResponseEvent;
import jain.protocol.ip.mgcp.message.DeleteConnection;
import jain.protocol.ip.mgcp.message.parms.EndpointIdentifier;
import jain.protocol.ip.mgcp.message.parms.NotifiedEntity;
import jain.protocol.ip.mgcp.message.parms.ReturnCode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mobicents.servlet.restcomm.patterns.Observe;
import org.mobicents.servlet.restcomm.patterns.Observing;
import org.mobicents.servlet.restcomm.patterns.StopObserving;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

/**
 * @author quintana.thomas@gmail.com (Thomas Quintana)
 */
public abstract class GenericEndpoint extends UntypedActor {

    protected final LoggingAdapter logger = Logging.getLogger(getContext().system(), this);

    protected final ActorRef gateway;
    protected final MediaSession session;
    protected final NotifiedEntity entity;
    protected EndpointIdentifier id;
    protected AtomicBoolean destroying;

    protected final List<ActorRef> observers;

    public GenericEndpoint(final ActorRef gateway, final MediaSession session, final NotifiedEntity entity,
            final EndpointIdentifier id) {
        super();
        this.gateway = gateway;
        this.session = session;
        this.entity = entity;
        this.id = id;
        this.destroying = new AtomicBoolean(false);
        this.observers = new ArrayList<ActorRef>(1);
    }

    @Override
    public void onReceive(final Object message) throws Exception {
        final Class<?> klass = message.getClass();
        final ActorRef self = self();
        final ActorRef sender = sender();

        if (Observe.class.equals(klass)) {
            onObserve((Observe) message, self, sender);
        } else if (StopObserving.class.equals(klass)) {
            onStopObserving((StopObserving) message, self, sender);
        } else if (InviteEndpoint.class.equals(klass)) {
            final EndpointCredentials credentials = new EndpointCredentials(id);
            sender.tell(credentials, self);
        } else if (UpdateEndpointId.class.equals(klass)) {
            final UpdateEndpointId request = (UpdateEndpointId) message;
            id = request.id();
        } else if (DestroyEndpoint.class.equals(klass)) {
            onDestroyEndpoint((DestroyEndpoint) message, self, sender);
        } else if (message instanceof JainMgcpResponseEvent) {
            onJainMgcpResponseEvent((JainMgcpResponseEvent) message, self, sender);
        }
        // TODO Add onReceiveTimeout
    }

    protected void onObserve(Observe message, ActorRef self, ActorRef sender) {
        final ActorRef observer = message.observer();
        if (observer != null) {
            synchronized (this.observers) {
                this.observers.add(observer);
                sender.tell(new Observing(self), self);
            }
        }
    }

    protected void onStopObserving(StopObserving message, ActorRef self, ActorRef sender) {
        final ActorRef observer = message.observer();
        if (observer != null) {
            observer.tell(message, self);
        }
    }

    protected void onDestroyEndpoint(DestroyEndpoint message, ActorRef self, ActorRef sender) {
        if (!this.destroying.get()) {
            this.destroying.set(true);
            DeleteConnection dlcx = new DeleteConnection(self, this.id);
            this.gateway.tell(dlcx, self);
            // TODO set timeout period
        }
    }

    protected void onJainMgcpResponseEvent(JainMgcpResponseEvent message, ActorRef self, ActorRef sender) {
        if (this.destroying.get()) {
            ReturnCode returnCode = message.getReturnCode();
            if (ReturnCode.TRANSACTION_EXECUTED_NORMALLY == returnCode.getValue()) {
                broadcast(new EndpointStateChanged(EndpointState.DESTROYED));
            } else {
                logger.error("Could not destroy endpoint " + this.id.toString() + ". Return Code: " + returnCode.toString());
            }
            // TODO remove timeout period
            // TODO tell akka to destroy actor
        }
    }

    protected void broadcast(final Object message) {
        if (!this.observers.isEmpty()) {
            final ActorRef self = self();
            for (ActorRef observer : observers) {
                observer.tell(message, self);
            }
        }
    }
}
