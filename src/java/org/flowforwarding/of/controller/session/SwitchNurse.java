/**
 * © 2013 FlowForwarding.Org
 * All Rights Reserved.  Use is subject to license terms.
 */

package org.flowforwarding.of.controller.session;

import org.flowforwarding.of.ofswitch.SwitchState.SwitchHandler;
import org.flowforwarding.of.protocol.ofmessages.IOFMessageProvider;
import org.flowforwarding.of.protocol.ofmessages.IOFMessageProviderFactory;
import org.flowforwarding.of.protocol.ofmessages.OFMessageFlowMod.OFMessageFlowModHandler;
import org.flowforwarding.of.protocol.ofmessages.OFMessageProviderFactoryAvroProtocol;
import org.flowforwarding.of.protocol.ofstructures.OFStructureInstruction.OFStructureInstructionHandler;

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
   private SwitchHandler swHandler;
   
   private ActorRef ofSessionHandler = null;
   private ActorRef tcpChannel = null;
   
   IOFMessageProviderFactory factory = new OFMessageProviderFactoryAvroProtocol();
   IOFMessageProvider provider = null;
   
   @Override
   public void preStart() throws Exception {
      super.preStart();
      
      swHandler = SwitchHandler.create();
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
               swHandler.setVersion(provider.getVersion());
               
               getSender().tell(TcpMessage.write(ByteString.fromArray(provider.encodeHelloMessage())), getSelf());
               this.state = State.CONNECTED;
               getSender().tell(TcpMessage.write(ByteString.fromArray(provider.encodeSwitchFeaturesRequest())), getSelf());    
               
               tcpChannel = getSender();
            }
            
            break;
         case CONNECTED:   
            in = ((Received) msg).data();
            
            if (provider.isSwitchFeatures(in.toArray())) {
               if (provider.getDPID(in.toArray()) != null) {
                  swHandler.setDpid(provider.getDPID(in.toArray()));
                  System.out.println("[OF-INFO] DPID: " + Long.toHexString(swHandler.getDpid().longValue()) +" Feature Reply is received from the Switch ");
                  System.out.println("[OF-INFO] Connected to Switch "+ Long.toHexString(swHandler.getDpid().longValue()));
                  state = State.HANDSHAKED;
                  
                  ofSessionHandler.tell(new OFEventHandshaked(swHandler), getSelf());
               }
            }
            break;
            
         case HANDSHAKED:
            in = ((Received) msg).data();
            
            if (provider.isConfig(in.toArray())) {
               System.out.println("[OF-INFO] DPID: " + Long.toHexString(swHandler.getDpid().longValue()) +" Switch Config is received from the Switch ");
               ofSessionHandler.tell(new OFEventSwitchConfig(swHandler, provider.parseSwitchConfig(in.toArray())), getSelf());
               
               OFMessageFlowModHandler flowModHandler = provider.buildFlowModMsg();
               flowModHandler.addField("priority", "32000");
               flowModHandler.addMatchInPort(swHandler.getDpid().toString().substring(0, 3));

               OFStructureInstructionHandler instruction = provider.buildInstructionApplyActions();
               instruction.addActionOutput("2");
               flowModHandler.addInstruction("apply_actions", instruction);

               instruction = provider.buildInstructionGotoTable();
               flowModHandler.addInstruction("goto_table", instruction);
               
               getSender().tell(TcpMessage.write(ByteString.fromArray(provider.encodeFlowMod(flowModHandler))), getSelf());
               
            } else if (provider.isPacketIn(in.toArray())) {
               System.out.println("[OF-INFO] DPID: " + Long.toHexString(swHandler.getDpid().longValue()) +" Packet-In is received from the Switch");
               ofSessionHandler.tell(new OFEventPacketIn(swHandler, provider.parsePacketIn(in.toArray())), getSelf());
            }  else if (provider.isError(in.toArray())) {
               System.out.println("[OF-INFO] DPID: " + Long.toHexString(swHandler.getDpid().longValue()) + " Error is received from the Switch ");
               ofSessionHandler.tell(new OFEventError(swHandler, provider.parseError(in.toArray())), getSelf());
            }  else if (provider.isEchoRequest(in.toArray())) {
               System.out.println("[OF-INFO] DPID: " + Long.toHexString(swHandler.getDpid().longValue()) + " Echo request is received from the Switch ");
               getSender().tell(TcpMessage.write(ByteString.fromArray(provider.encodeEchoReply())), getSelf());
            }
            
            //ofSessionHandler.tell(new OFEventIncoming(swHandler), getSelf());
            
            OFMessageFlowModHandler flowModHandler = provider.buildFlowModMsg();
            flowModHandler.addField("priority", "32000");
            flowModHandler.addMatchInPort(swHandler.getDpid().toString().substring(0, 3));

            OFStructureInstructionHandler instruction = provider.buildInstructionApplyActions();
            instruction.addActionOutput("2");
            flowModHandler.addInstruction("apply_actions", instruction);

            instruction = provider.buildInstructionGotoTable();
            flowModHandler.addInstruction("goto_table", instruction);
            
            getSender().tell(TcpMessage.write(ByteString.fromArray(provider.encodeFlowMod(flowModHandler))), getSelf());
            
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
