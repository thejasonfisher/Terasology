package org.terasology.network;

import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFactory;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.EntityChangeSubscriber;
import org.terasology.entitySystem.EntityManager;
import org.terasology.entitySystem.EntityRef;
import org.terasology.entitySystem.PersistableEntityManager;
import org.terasology.entitySystem.metadata.extension.EntityRefTypeHandler;
import org.terasology.entitySystem.persistence.EntitySerializer;
import org.terasology.game.CoreRegistry;
import org.terasology.protobuf.NetData;
import org.terasology.world.chunks.Chunk;
import org.terasology.world.chunks.remoteChunkProvider.RemoteChunkProvider;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;

/**
 * @author Immortius
 */
public class NetworkSystem implements EntityChangeSubscriber {
    private static final Logger logger = LoggerFactory.getLogger(NetworkSystem.class);

    private NetworkMode mode = NetworkMode.NONE;
    private Set<ClientPlayer> clientPlayerList = Sets.newLinkedHashSet();

    // Shared
    private PersistableEntityManager entityManager;
    private ChannelFactory factory;
    private TIntIntMap netIdToEntityId = new TIntIntHashMap();
    private BlockingQueue<NetData.NetMessage> queuedMessages = Queues.newLinkedBlockingQueue();

    // Server only
    private ChannelGroup allChannels = new DefaultChannelGroup("tera-server");
    private NetData.ServerInfoMessage serverInfo;
    private int nextNetId = 1;

    // Client only
    private RemoteChunkProvider remoteWorldProvider;
    private BlockingQueue<Chunk> chunkQueue = Queues.newLinkedBlockingQueue();

    public NetworkSystem() {
    }

    public void host(int port) {
        if (mode == NetworkMode.NONE) {
            factory = new NioServerSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
            ServerBootstrap bootstrap = new ServerBootstrap(factory);
            bootstrap.setPipelineFactory(new TerasologyServerPipelineFactory(this));
            bootstrap.setOption("child.tcpNoDelay", true);
            bootstrap.setOption("child.keepAlive", true);
            Channel listenChannel = bootstrap.bind(new InetSocketAddress(port));
            allChannels.add(listenChannel);
            logger.info("Started server");
            mode = NetworkMode.SERVER;
        }
    }

    public boolean join(String address, int port) {
        if (mode == NetworkMode.NONE) {
            factory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
            ClientBootstrap bootstrap = new ClientBootstrap(factory);
            bootstrap.setPipelineFactory(new TerasologyClientPipelineFactory(this));
            bootstrap.setOption("tcpNoDelay", true);
            bootstrap.setOption("keepAlive", true);
            ChannelFuture connectCheck = bootstrap.connect(new InetSocketAddress(address, port));
            connectCheck.awaitUninterruptibly();
            if (!connectCheck.isSuccess()) {
                logger.warn("Failed to connect to server", connectCheck.getCause());
                connectCheck.getChannel().getCloseFuture().awaitUninterruptibly();
                factory.releaseExternalResources();
                return false;
            } else {
                allChannels.add(connectCheck.getChannel());
                logger.info("Connected to server");
                mode = NetworkMode.CLIENT;
                return true;
            }
        }
        return false;
    }

    public void send(NetData data) {
        allChannels.write(data);
    }

    public void sendChatMessage(String message) {
        NetData.NetMessage data = NetData.NetMessage.newBuilder()
                .setType(NetData.NetMessage.Type.CONSOLE)
                .setConsole(NetData.ConsoleMessage.newBuilder().setMessage(message))
                .build();
        allChannels.write(data);
    }

    public void shutdown() {
        if (mode != NetworkMode.NONE) {
            allChannels.close().awaitUninterruptibly();
            factory.releaseExternalResources();
            logger.info("Network shutdown");
            mode = NetworkMode.NONE;
            serverInfo = null;
            remoteWorldProvider = null;
            nextNetId = 1;
            netIdToEntityId.clear();
        }
    }

    public void update() {
        if (mode != NetworkMode.NONE) {
            if (remoteWorldProvider != null) {
                List<Chunk> chunks = Lists.newArrayListWithExpectedSize(chunkQueue.size());
                chunkQueue.drainTo(chunks);
                for (Chunk chunk : chunks) {
                    remoteWorldProvider.receiveChunk(chunk);
                }
            }
            for (ClientPlayer client : clientPlayerList) {
                client.update();
            }
            processMessages();
        }
    }

    private void processMessages() {
        List<NetData.NetMessage> messages = Lists.newArrayListWithExpectedSize(queuedMessages.size());
        queuedMessages.drainTo(messages);
        EntitySerializer serializer = new EntitySerializer(entityManager);
        serializer.setIgnoringEntityId(true);
        EntityRefTypeHandler.setNetworkMode(this);

        for (NetData.NetMessage message : messages) {
            switch (message.getType()) {
                case CREATE_ENTITY:
                    EntityRef newEntity = serializer.deserialize(message.getCreateEntity().getEntity());
                    registerNetworkEntity(newEntity);
                    break;
                case UPDATE_ENTITY:
                    EntityRef currentEntity = getEntity(message.getUpdateEntity().getNetId());
                    serializer.deserializeOnto(currentEntity, message.getUpdateEntity().getEntity());
                    break;
                case REMOVE_ENTITY:
                    int netId = message.getRemoveEntity().getNetId();
                    EntityRef entity = getEntity(netId);
                    unregisterNetworkEntity(entity);
                    entity.destroy();
                    break;
            }
        }

        EntityRefTypeHandler.setEntityManagerMode(entityManager);
    }

    public NetworkMode getMode() {
        return mode;
    }

    public NetData.ServerInfoMessage getServerInfo() {
        return serverInfo;
    }

    public void setServerInfo(NetData.ServerInfoMessage serverInfo) {
        this.serverInfo = serverInfo;
    }


    public void setRemoteWorldProvider(RemoteChunkProvider remoteWorldProvider) {
        this.remoteWorldProvider = remoteWorldProvider;
    }

    public RemoteChunkProvider getRemoteWorldProvider() {
        return remoteWorldProvider;
    }


    public void registerNetworkEntity(EntityRef entity) {
        if (mode == NetworkMode.NONE) {
            return;
        }

        NetworkComponent netComponent = entity.getComponent(NetworkComponent.class);
        if (mode == NetworkMode.SERVER) {
            netComponent.networkId = nextNetId++;
            entity.saveComponent(netComponent);
        }

        netIdToEntityId.put(netComponent.networkId, entity.getId());

        if (mode == NetworkMode.SERVER) {
            for (ClientPlayer client : clientPlayerList) {
                // TODO: Relevance Check
                client.setNetInitial(netComponent.networkId);
            }
        }
    }

    public void unregisterNetworkEntity(EntityRef entity) {
        NetworkComponent netComponent = entity.getComponent(NetworkComponent.class);
        netIdToEntityId.remove(netComponent.networkId);
        if (mode == NetworkMode.SERVER) {
            NetData.NetMessage message = NetData.NetMessage.newBuilder()
                    .setType(NetData.NetMessage.Type.REMOVE_ENTITY)
                    .setRemoveEntity(
                            NetData.RemoveEntityMessage.newBuilder().setNetId(netComponent.networkId).build())
                    .build();
            for (ClientPlayer client : clientPlayerList) {
                client.setNetRemoved(netComponent.networkId, message);
            }
        }
    }

    public void setEntityManager(PersistableEntityManager entityManager) {
        if (this.entityManager != null) {
            this.entityManager.unsubscribe(this);
        }
        this.entityManager = entityManager;
        this.entityManager.subscribe(this);
    }

    @Override
    public void onEntityChange(EntityRef entity) {
        NetworkComponent netComp = entity.getComponent(NetworkComponent.class);
        if (netComp != null) {
            for (ClientPlayer client : clientPlayerList) {
                client.setNetDirty(netComp.networkId);
            }
        }
    }

    EntityRef getEntity(int netId) {
        int entityId = netIdToEntityId.get(netId);
        if (entityId != 0) {
            return CoreRegistry.get(EntityManager.class).getEntity(entityId);
        }
        return EntityRef.NULL;
    }

    void registerChannel(Channel channel) {
        allChannels.add(channel);
    }

    void addClient(ClientPlayer client) {
        synchronized (clientPlayerList) {
            clientPlayerList.add(client);
        }
        for (EntityRef netEntity : entityManager.iteratorEntities(NetworkComponent.class)) {
            NetworkComponent netComp = netEntity.getComponent(NetworkComponent.class);
            // TODO: Relevance Check
            client.setNetInitial(netComp.networkId);
        }
    }

    void removeClient(ClientPlayer client) {
        synchronized (clientPlayerList) {
            clientPlayerList.remove(client);
        }
    }

    void receiveChunk(Chunk chunk) {
        chunkQueue.offer(chunk);
    }

    void queueMessage(NetData.NetMessage message) {
        queuedMessages.offer(message);
    }
}
