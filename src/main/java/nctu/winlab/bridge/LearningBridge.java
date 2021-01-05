/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nctu.winlab.bridge;

import com.google.common.collect.ImmutableSet;
import org.onosproject.cfg.ComponentConfigService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Properties;

import static org.onlab.util.Tools.get;

/**
 * Additional imported pkgs
 */
import java.util.HashMap;
import java.util.Map;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.PortNumber;
import org.onosproject.net.host.HostService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
           service = LearningBridge.class/*,
           property = {

           }*/
)

public class LearningBridge{

    private final Logger log = LoggerFactory.getLogger(getClass());

    /** Some configurable property. */
    private String someProperty;

    private ApplicationId appId;

    private BridgePacketProcessor processor = new BridgePacketProcessor();

    private HashMap<DeviceId, HashMap<MacAddress, PortNumber>> mapDevice =
            new HashMap<DeviceId, HashMap<MacAddress, PortNumber>>();

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Activate
    protected void activate() {
        //cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("winlab.nctu.lb");
        packetService.addProcessor(processor, PacketProcessor.director(2));
        controllerRequests();
        log.info("Started", appId.id());
    }

    @Deactivate
    protected void deactivate() {
        //cfgService.unregisterProperties(getClass(), false);
        controllerWithdraws();
        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }

    //dont need this?
    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        log.info("Reconfigured");
    }

    private void controllerRequests(){
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void controllerWithdraws(){
	      TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
	      selector.matchEthType(Ethernet.TYPE_IPV4);
	      packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private class BridgePacketProcessor implements PacketProcessor{
        @Override
        public void process(PacketContext context){
            if(context.isHandled()) return;

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if(ethPkt == null){
                return;
            }

            DeviceId fromDevice = pkt.receivedFrom().deviceId();
            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();
            log.info("Packet from device {}", fromDevice);
            if(ethPkt.isBroadcast()){
                if(!mapDevice.containsKey(fromDevice)){
                    HashMap<MacAddress, PortNumber> temp = new HashMap<MacAddress, PortNumber>();
                    mapDevice.put(fromDevice, temp);
                }
                if(!mapDevice.get(fromDevice).containsKey(srcMac)){
                    mapDevice.get(fromDevice).put(srcMac, pkt.receivedFrom().port());
                    log.info("Add MAC address ==> switch: {}, MAC: {}, port: {}",
                              fromDevice, srcMac, pkt.receivedFrom().port());
                }
                flood(context);
                return;
            }

            if(!mapDevice.containsKey(fromDevice)){
                HashMap<MacAddress, PortNumber> temp = new HashMap<MacAddress, PortNumber>();
                mapDevice.put(fromDevice, temp);
            }
            //log.info("Device {} has info on mac src {}: {}", fromDevice, srcMac, mapDevice.get(fromDevice).containsKey(srcMac));
            if(!mapDevice.get(fromDevice).containsKey(srcMac)){
                mapDevice.get(fromDevice).put(srcMac, pkt.receivedFrom().port());
                log.info("Add MAC address ==> switch: {}, MAC: {}, port: {}",
                          fromDevice, srcMac, pkt.receivedFrom().port());
            }
            //log.info("Device {} has info on mac dst {}: {}", fromDevice, dstMac, mapDevice.get(fromDevice).containsKey(dstMac));
            if(mapDevice.get(fromDevice).containsKey(dstMac)){
                installRule(context, mapDevice.get(fromDevice).get(dstMac));
                log.info("MAC {} is matched on {}! Install flow rule!",
                          dstMac, fromDevice);
            } else {
                flood(context);
                log.info("MAC {} is missed on {}! Flood packet!",
                          dstMac, fromDevice);
            }
        }
    }

    private void installRule(PacketContext context, PortNumber portNumber){
        Ethernet inPkt = context.inPacket().parsed();
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthSrc(inPkt.getSourceMAC()).matchEthDst(inPkt.getDestinationMAC());
        TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(portNumber).build();
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                                                                            .withSelector(selectorBuilder.build())
                                                                            .withTreatment(treatment)
                                                                            .withPriority(20)
                                                                            .withFlag(ForwardingObjective.Flag.VERSATILE)
                                                                            .fromApp(appId)
                                                                            .makeTemporary(20)
                                                                            //.makePermanent()
                                                                            .add();
        flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(), forwardingObjective);
        packetOut(context, portNumber);
    }

    private void flood(PacketContext context){
        packetOut(context, PortNumber.FLOOD);
    }

    private void packetOut(PacketContext context, PortNumber portNumber){
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }
}
