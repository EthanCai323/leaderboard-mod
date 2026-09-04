package com.ethan.leaderboard;

import com.mojang.authlib.GameProfile;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.NameToIdCache;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家头颅 ProfileComponent 解析。
 * 尽量从在线玩家 / 服务器 NameToIdCache（原 usercache）获取带 UUID 与纹理属性的 GameProfile，
 * 使头颅显示玩家皮肤；全部拿不到时退化为仅名字（原行为）。
 * 全程只读本地缓存与内存数据，不发起网络请求，离线模式服务器不会因此卡顿。
 */
final class PlayerHeads {
    /** 名字（小写）-> 已解析的 ProfileComponent，会话内缓存 */
    private static final Map<String, ProfileComponent> CACHE = new ConcurrentHashMap<>();

    private PlayerHeads() {
    }

    static ProfileComponent profileFor(MinecraftServer server, String name) {
        return CACHE.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> resolve(server, name));
    }

    private static ProfileComponent resolve(MinecraftServer server, String name) {
        if (server != null) {
            // 每一步独立防御：任何 API 变动或实现缺失都安全降级到仅名字
            try {
                // 在线玩家的 GameProfile 自带纹理属性，皮肤可直接显示
                ServerPlayerEntity online = server.getPlayerManager().getPlayer(name);
                if (online != null) {
                    return ProfileComponent.ofStatic(online.getGameProfile());
                }
            } catch (Exception | NoClassDefFoundError | NoSuchMethodError ignored) {
                // 继续尝试 NameToIdCache
            }
            try {
                // NameToIdCache 提供 UUID（一般无纹理），客户端可凭 UUID 异步补全皮肤
                NameToIdCache cache = server.getApiServices().nameToIdCache();
                if (cache != null) {
                    Optional<PlayerConfigEntry> cached = cache.findByName(name);
                    if (cached.isPresent() && cached.get().name() != null) {
                        return ProfileComponent.ofStatic(
                                new GameProfile(cached.get().id(), cached.get().name()));
                    }
                }
            } catch (Exception | NoClassDefFoundError | NoSuchMethodError ignored) {
                // 退化为仅名字
            }
        }
        return ProfileComponent.ofDynamic(name);
    }
}
