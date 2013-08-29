/**
 * © 2013 FlowForwarding.Org
 * All Rights Reserved.  Use is subject to license terms.
 */

package org.flowforwarding.of.controller.session;

import java.io.ByteArrayOutputStream;

import org.flowforwarding.of.controller.protocol.OFMessageProvider;
import org.flowforwarding.of.controller.protocol.OFMessageProviderFactory;
import org.flowforwarding.of.controller.protocol.OFMessageProviderFactoryAvroProtocol;
import org.flowforwarding.of.ofswitch.SwitchState;
import org.flowforwarding.of.ofswitch.SwitchState.SwitchRef;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.io.TcpMessage;
import akka.io.Tcp.ConnectionClosed;
import akka.io.Tcp.Received;
import akka.util.ByteString;

public class SwitchNurse extends UntypedActor {
   
   private enum State {
      STARTED,
      CONNECTED,
      HANDSHAKED,
      CONFIG_READY,
      READY
   }
   
   private State state = State.STARTED;
   private SwitchRef switchRef;
   
   private ActorRef ofSessionHandler = null;
   private ActorRef tcpChannel = null;
   
   OFMessageProviderFactory factory = new OFMessageProviderFactoryAvroProtocol();
   OFMessageProvider provider = null;
   
   @Override
   public void preStart() throws Exception {
      super.preStart();
      
      switchRef = SwitchRef.create();
   }
   @Override
   public void onReceive(Object msg) throws Exception {
      if (msg instanceof Received) {
         
         switch (this.state) {
         case STARTED:
            ByteString in = ((Received) msg).data();
            provider = factory.getMessageProvider(in.toArray());
            
            if (provider != null) {
               
               provider.init();
               switchRef.setVersion(provider.getVersion());
               
               getSender().tell(TcpMessage.write(ByteString.fromArray(provider.encodeHelloMessage())), getSelf());
               this.state = State.CONNECTED;
               getSender().tell(TcpMessage.write(ByteString.fromArray(provider.encodeSwitchFeaturesRequest())), getSelf());    
               
               tcpChannel = getSender();
            }
            
            break;
         case CONNECTED:   
            in = ((Received) msg).data();
            
            if (provider.getDPID(in.toArray()) != null) {
               switchRef.setDpid(provider.getDPID(in.toArray()));
               
               System.out.println("[INFO-OF] Connected to Switch "+ Long.toHexString(switchRef.getDpid().longValue()));
               state = State.HANDSHAKED;
               
               ofSessionHandler.tell(new OFEventHandshaked(switchRef), getSelf());
            }
            
            break;
            
         case HANDSHAKED:
            in = ((Received) msg).data();
            
            ofSessionHandler.tell(new OFIncoming(switchRef), getSelf());
            
            break;
         default:
            break;
            
         }

      } else if (msg instanceof ConnectionClosed) {
         getContext().stop(getSelf());
      } else if (msg instanceof ActorRef) {
         ofSessionHandler = (ActorRef) msg;
      } else if (msg instanceof OFCommandSendSwConfigRequest) {
         tcpChannel.tell(TcpMessage.write(ByteString.fromArray(provider.encodeSwitchConfigRequest())), getSelf());
      }
   }

}
