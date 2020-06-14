package de.cgrotz.kademlia;

import de.cgrotz.kademlia.client.KademliaClient;
import de.cgrotz.kademlia.config.Listener;
import de.cgrotz.kademlia.config.UdpListener;
import de.cgrotz.kademlia.events.Event;
import de.cgrotz.kademlia.exception.KademliaTimeoutException;
import de.cgrotz.kademlia.node.Key;
import de.cgrotz.kademlia.node.Node;
import de.cgrotz.kademlia.protocol.ValueReply;
import de.cgrotz.kademlia.routing.RoutingTable;
import de.cgrotz.kademlia.server.KademliaServer;
import de.cgrotz.kademlia.storage.InMemoryStorage;
import de.cgrotz.kademlia.storage.LocalStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Created by Christoph on 21.09.2016.
 */
public class Kademlia {
    private static final Logger LOGGER = LoggerFactory.getLogger(Kademlia.class);

    protected final RoutingTable routingTable;
    protected final KademliaClient client;
    protected final List<KademliaServer> servers = new ArrayList<>();

    protected final LocalStorage localStorage;
    protected final Node localNode;

    protected final Configuration config;

    private final Map<String, Consumer<Event>> eventListeners = new HashMap<>();

    public Kademlia(Key nodeId) {
        this(Configuration.defaults()
                .nodeId(nodeId)
                .build());
    }

    public Kademlia(Key nodeId, String listeners) {
        this(nodeId, listeners, new InMemoryStorage());
    }


    public Kademlia(Key nodeId, String listeners, LocalStorage localStorage) {
        this(Configuration.defaults()
                .nodeId(nodeId)
                .listeners(
                        Arrays.stream(listeners.split(",")).map(Listener::fromUrl).collect(Collectors.toList())
                )
                .advertisedListeners(
                        Arrays.stream(listeners.split(",")).map(Listener::fromUrl).collect(Collectors.toList())
                )
                .build(), localStorage);
    }

    public Kademlia(Configuration config){
        this(config, new InMemoryStorage());
    }

    public Kademlia(Configuration config, LocalStorage localStorage){
        this.config = config;
        this.localNode = Node.builder().id(config.getNodeId())
                .advertisedListeners(config.getAdvertisedListeners())
                .build();

        try {
            this.client = new KademliaClient(new DatagramSocket(), config, localNode);
        } catch (SocketException e) {
            throw new RuntimeException("Couldn't start client", e);
        }

        this.routingTable = new RoutingTable(config.getKValue(), config.getNodeId(), client);
        this.localStorage =  localStorage;

        config.getListeners().stream().filter(listener -> listener instanceof UdpListener)
                .map(listener -> (UdpListener)listener)
                .forEach( listener ->  {
                    try {
                        this.servers.add(new KademliaServer(listener.getHost(), listener.getPort(),
                                config.getKValue(), routingTable, localStorage, localNode, eventListeners));
                    } catch (SocketException e) {
                        e.printStackTrace();
                    }
                });
    }

    public void bootstrap(Node bootstrapNode) {
        LOGGER.debug("bootstrapping node={}", localNode);

        client.sendPing(bootstrapNode, pong -> {
            LOGGER.debug("bootstrapping node={}, ping from remote={} received", localNode, bootstrapNode);
            routingTable.addNode(pong.getOrigin());
        });

        // FIND_NODE with own IDs to find nearby nodes
        client.sendFindNode(bootstrapNode, localNode.getId(), nodes -> {
            LOGGER.debug("bootstrapping node={}, sendFind node from remote={} received, nodes={}", localNode, bootstrapNode, nodes.size());
            nodes.stream().forEach(node -> routingTable.addNode(node));
        });

        LOGGER.debug("bootstrapping node={}, refreshing buckets", localNode);
        refreshBuckets();
    }

    /**
     * Put or Update the value in the DHT
     *
     * @param key
     * @param value
     */
    public void put(Key key, String value) {
        client.sendFindNode(localNode, key, nodes -> {
            nodes.stream().forEach(node -> {
                client.sendContentToNode( node, key ,value);
            });
        });
    }

    /**
     *
     * Retrieve the Value associated with the Key
     *
     * @param key
     * @return
     */
    public String get(Key key) {
        CompletableFuture<String> future = new CompletableFuture<>();
        new Thread(() -> {
            get(key, valueReply -> {
                future.complete(valueReply.getValue());
            });
        }).start();
        try {
            return future.get(config.getGetTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException | TimeoutException | ExecutionException e) {
            throw new KademliaTimeoutException(e);
        }
    }


    public void get(Key key, Consumer<ValueReply> valueReplyConsumer) {
        if(localStorage.contains(key)) {
            valueReplyConsumer.accept(new ValueReply(-1,localNode, key, localStorage.get(key).getContent()));
        }
        else {
            HashSet<Node> alreadyCheckedNodes = new HashSet<>();
            AtomicBoolean found = new AtomicBoolean(false);
            List<Node> nodes = routingTable.getBucketStream()
                    .flatMap(bucket -> bucket.getNodes().stream())
                    .sorted((node1, node2) -> node1.getId().getKey().xor(key.getKey()).abs()
                            .compareTo(node2.getId().getKey().xor(key.getKey()).abs()))
                    .collect(Collectors.toList());

            get(found, key, nodes, alreadyCheckedNodes, valueReply -> {
                        if(!found.getAndSet(true)) {
                            valueReplyConsumer.accept(valueReply);
                        }
                    });
        }
    }

    private void get(AtomicBoolean found, Key key, List<Node> nodes, HashSet<Node> alreadyCheckedNodes, Consumer<ValueReply> valueReplyConsumer) {
        for( Node node : nodes) {
            if(!alreadyCheckedNodes.contains(node) && !found.get()) {
                client.sendFindValue(node,
                        key, nodeReply -> {
                            nodeReply.getNodes().stream().forEach(newNode -> routingTable.addNode(newNode));
                            get(found, key, nodeReply.getNodes(), alreadyCheckedNodes, valueReplyConsumer);
                        }, valueReplyConsumer);

                alreadyCheckedNodes.add(node);
            }
        }
    }

    /**
     * Execute key republishing
     *
     * Iterate over all keys that weren't updated within the last hour and republish
     * to the other k-nodes that are closest to the associated keys
     */
    public void republishKeys() {
        KeyRepublishing.builder()
                .kademliaClient(client)
                .localStorage(localStorage)
                .routingTable(routingTable)
                .k(config.getKValue())
                .build().execute();
    }

    public void refreshBuckets() {
        // Refresh buckets
        for (int i = 1; i < Key.ID_LENGTH; i++) {
            // Construct a Key that is i bits away from the current node Id
            final Key current = this.localNode.getId().generateNodeIdByDistance(i);

            routingTable.getBucketStream()
                    .flatMap(bucket -> bucket.getNodes().stream())
                    .forEach(node -> {
                        client.sendFindNode(node, current, nodes -> {
                            nodes.stream().forEach(newNode -> routingTable.addNode(newNode));
                        });
                    });

        }
    }

    public RoutingTable getRoutingTable() {
        return routingTable;
    }

    public Node getLocalNode() {
        return localNode;
    }

    public void close() {
        servers.forEach(KademliaServer::close);
        client.close();
    }

    public void addEventListener(String registrationId, Consumer<Event> eventConsumer) {
        this.eventListeners.put(registrationId, eventConsumer);
    }

    public void removeEventListener(String registrationId) {
        this.eventListeners.remove(registrationId);
    }
}
