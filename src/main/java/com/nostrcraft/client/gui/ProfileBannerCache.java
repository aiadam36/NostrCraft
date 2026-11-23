package com.nostrcraft.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded asynchronous cache for untrusted profile banner images. */
final class ProfileBannerCache {
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 4_000_000L;
    private static final ExecutorService DOWNLOADS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "NostrCraft-ProfileBanner");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<String, Entry>(16, 0.75f, true);
    private static int nextTextureId;

    private static final class Entry {
        boolean loading = true;
        ResourceLocation texture;
        int width;
        int height;
    }

    private ProfileBannerCache() {}

    static Banner get(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (ENTRIES) {
            Entry entry = ENTRIES.get(url);
            if (entry == null) {
                entry = new Entry();
                ENTRIES.put(url, entry);
                trimCache();
                final Entry requested = entry;
                DOWNLOADS.execute(() -> download(url, requested));
            }
            return entry.loading || entry.texture == null ? null : new Banner(entry.texture, entry.width, entry.height);
        }
    }

    private static void trimCache() {
        Iterator<Map.Entry<String, Entry>> iterator = ENTRIES.entrySet().iterator();
        while (ENTRIES.size() > MAX_ENTRIES && iterator.hasNext()) {
            Entry old = iterator.next().getValue();
            iterator.remove();
            if (old.texture != null) {
                ResourceLocation texture = old.texture;
                Minecraft.getMinecraft().addScheduledTask(() -> Minecraft.getMinecraft().getTextureManager().deleteTexture(texture));
            }
        }
    }

    private static void download(String address, Entry entry) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(address);
            String protocol = url.getProtocol();
            if (!("https".equalsIgnoreCase(protocol) || "http".equalsIgnoreCase(protocol))
                    || url.getHost().isEmpty() || url.getUserInfo() != null) return;
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "image/png,image/jpeg,image/gif,image/webp");
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) return;
            int length = connection.getContentLength();
            if (length > MAX_BYTES) return;
            byte[] bytes = readBounded(connection.getInputStream());
            BufferedImage image = decode(bytes);
            if (image == null) return;
            Minecraft.getMinecraft().addScheduledTask(() -> install(address, entry, image));
        } catch (Exception ignored) {
            // Invalid URLs, unsupported image formats, and network errors leave the banner hidden.
        } finally {
            if (connection != null) connection.disconnect();
            synchronized (ENTRIES) { entry.loading = false; }
        }
    }

    private static byte[] readBounded(InputStream input) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_BYTES) throw new IllegalArgumentException("Banner image is too large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static BufferedImage decode(byte[] bytes) throws Exception {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
                        || (long) width * height > MAX_PIXELS) return null;
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void install(String address, Entry entry, BufferedImage image) {
        synchronized (ENTRIES) {
            if (ENTRIES.get(address) != entry) return;
            ResourceLocation location = new ResourceLocation("nostrcraft", "profile_banner_" + nextTextureId++);
            DynamicTexture dynamicTexture = new DynamicTexture(image);
            Minecraft.getMinecraft().getTextureManager().loadTexture(location, dynamicTexture);
            entry.texture = location;
            entry.width = image.getWidth();
            entry.height = image.getHeight();
            entry.loading = false;
        }
    }

    static final class Banner {
        final ResourceLocation texture;
        final int width;
        final int height;

        Banner(ResourceLocation texture, int width, int height) {
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }
}
