package emu.protoshift.server.game;

import emu.protoshift.ProtoShift;
import emu.protoshift.net.packet.*;
import emu.protoshift.server.packet.injecter.Handle;

import emu.protoshift.utils.ConfigContainer;
import emu.protoshift.utils.Crypto;
import emu.protoshift.utils.Utils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.reflections.Reflections;

import java.util.Map;
import java.util.HashMap;

public final class GameServerPacketHandler {
    public static final Map<Integer, PacketHandler> newHandlers = new HashMap<>();
    public static final Map<Integer, PacketHandler> oldHandlers = new HashMap<>();

    static {
        Reflections reflections = new Reflections("emu.protoshift.server.packet");

        for (var obj : reflections.getSubTypesOf(PacketHandler.class)) {
            registerPacketHandler(obj);
        }

        // Debug
        ProtoShift.getLogger().info("Registered newHandlers " + newHandlers.size() + " " + PacketHandler.class.getSimpleName() + "s");
        ProtoShift.getLogger().info("Registered oldHandlers " + oldHandlers.size() + " " + PacketHandler.class.getSimpleName() + "s");
    }

    public static void registerPacketHandler(Class<? extends PacketHandler> handlerClass) {
        try {
            Opcodes opcode = handlerClass.getAnnotation(Opcodes.class);

            if (opcode == null || opcode.value() <= 0)
                return;

            PacketHandler packetHandler = handlerClass.getDeclaredConstructor().newInstance();

            if (opcode.type() == 1) {
                newHandlers.put(opcode.value(), packetHandler);
            } else {
                oldHandlers.put(opcode.value(), packetHandler);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void handlePacket(GameSession session, byte[] bytes, boolean isFromServer) {
        boolean isUseDispatchKey = (bytes[0] == (byte) (0x45 ^ Crypto.DISPATCH_KEY[0]) && bytes[1] == (byte) (0x67 ^ Crypto.DISPATCH_KEY[1]));

        // Decrypt and turn back into a packet
        if (isUseDispatchKey)
            Crypto.xor(bytes, Crypto.DISPATCH_KEY, false);
        else
            Crypto.xor(bytes, session.getEncryptKey(), false);

        ByteBuf packet = Unpooled.wrappedBuffer(bytes);

        // Handle
        try {
            boolean allDebug = ProtoShift.getConfig().server.debugLevel == ConfigContainer.ServerDebugMode.ALL;
            while (packet.readableBytes() > 12) {
                // Packet sanity check
                int const1 = packet.readUnsignedShort();
                if (const1 != 0x4567) {
                    if (allDebug) {
                        ProtoShift.getLogger().error("Bad Data Package Received from " + (isFromServer ? "server" : "client") + ": got " + const1 + " ,expect 0x4567\n" + Utils.bytesToHex(bytes));
                    }
                    return; // Bad packet
                }
                // Data
                int opcode = packet.readShort();
                int headerLength = packet.readShort();
                int payloadLength = packet.readInt();
                byte[] header = new byte[headerLength];
                byte[] payload = new byte[payloadLength];

                packet.readBytes(header);
                packet.readBytes(payload);
                // Sanity check #2
                int const2 = packet.readUnsignedShort();
                if (const2 != 0x89ab) {
                    if (allDebug) {
                        ProtoShift.getLogger().error("Bad Data Package Received " + (isFromServer ? "server" : "client") + ": got " + const2 + " ,expect 0x89ab\n" + Utils.bytesToHex(bytes));
                    }
                    return; // Bad packet
                }
                // Handle
                handle(session, new PacketOpcodes(opcode, isFromServer ? 2 : 1), header, payload, isUseDispatchKey);
            }
        } catch (Exception e) {
            ProtoShift.getLogger().error("Error handling packet: " + e.getMessage());
        } finally {
            //byteBuf.release(); //Needn't
            packet.release();
        }
    }

    public static void handle(GameSession session, PacketOpcodes opcode, byte[] header, byte[] payload, boolean isUseDispatchKey) {
        if (ProtoShift.getConfig().server.debugLevel == ConfigContainer.ServerDebugMode.ALL) {
            ProtoShift.getLogger().debug("Receive packet (" + opcode.value + ", " + opcode.type + "): " + PacketOpcodesUtil.getOpcodeName(opcode) + "\n"
                    + Utils.bytesToHex(payload));
        }

        PacketHandler handler = (opcode.type == 1 ? newHandlers.get(opcode.value) : oldHandlers.get(opcode.value));

        if (handler != null) {
            try {
                handler.handle(session, header, Handle.preHandle(session, opcode, payload), isUseDispatchKey);
            } catch (IllegalStateException ignored) {
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } else ProtoShift.getLogger()
                .error("packet (" + opcode.value + ", " + opcode.type + "): " + PacketOpcodesUtil.getOpcodeName(opcode) + " don't have handler!");
    }
}
