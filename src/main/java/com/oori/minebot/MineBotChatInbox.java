package com.oori.minebot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.UUID;
import net.minecraft.util.math.Vec3d;

public final class MineBotChatInbox {
    public static final int CAPACITY = 64;
    public static final long MAX_AGE_MILLIS = 600_000L;

    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private long nextId = 1L;
    private int dropped;

    public void add(Message message, String address) {
        if (this.entries.size() >= CAPACITY) {
            this.entries.removeFirst();
            this.dropped++;
        }

        this.entries.addLast(new Entry(this.nextId++, message, address));
    }

    public JsonObject read(boolean peek, int limit, String robotDimension, Vec3d robotPos) {
        long now = System.currentTimeMillis();
        while (!this.entries.isEmpty() && now - this.entries.peekFirst().message().timestampMillis() > MAX_AGE_MILLIS) {
            this.entries.removeFirst();
            this.dropped++;
        }

        int count = Math.min(limit, this.entries.size());
        JsonArray messages = new JsonArray();
        Iterator<Entry> iterator = this.entries.iterator();
        for (int index = 0; index < count; index++) {
            messages.add(describe(iterator.next(), now, robotDimension, robotPos));
        }

        if (!peek) {
            for (int index = 0; index < count; index++) {
                this.entries.removeFirst();
            }
        }

        JsonObject result = new JsonObject();
        result.add("messages", messages);
        result.addProperty("remaining", this.entries.size());
        result.addProperty("dropped", this.dropped);

        if (!peek) {
            this.dropped = 0;
        }
        return result;
    }

    private static JsonObject describe(Entry entry, long now, String robotDimension, Vec3d robotPos) {
        Message message = entry.message();
        JsonObject description = new JsonObject();
        description.addProperty("id", entry.id());
        description.addProperty("sender", message.sender());
        if (message.senderUuid() != null) {
            description.addProperty("sender_uuid", message.senderUuid().toString());
        }
        description.addProperty("sender_type", message.senderType());
        description.addProperty("text", message.text());
        description.addProperty("raw", message.raw());
        description.addProperty("address", entry.address());
        description.addProperty("timestamp_ms", message.timestampMillis());
        description.addProperty("age_seconds", Math.round((now - message.timestampMillis()) / 100.0D) / 10.0D);
        if (message.senderDimension() != null) {
            description.addProperty("sender_dimension", message.senderDimension());
        }
        if (message.senderPos() != null) {
            description.addProperty("sender_x", MineBotEntity.roundCoordinate(message.senderPos().x));
            description.addProperty("sender_y", MineBotEntity.roundCoordinate(message.senderPos().y));
            description.addProperty("sender_z", MineBotEntity.roundCoordinate(message.senderPos().z));
            if (message.senderDimension() != null && message.senderDimension().equals(robotDimension)) {
                description.addProperty("distance", MineBotEntity.roundCoordinate(message.senderPos().distanceTo(robotPos)));
            }
        }
        return description;
    }

    public record Message(
        String sender,
        UUID senderUuid,
        String senderType,
        String text,
        String raw,
        long timestampMillis,
        String senderDimension,
        Vec3d senderPos
    ) {
    }

    private record Entry(long id, Message message, String address) {
    }
}
