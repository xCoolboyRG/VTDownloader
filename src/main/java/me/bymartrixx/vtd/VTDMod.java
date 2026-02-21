package me.bymartrixx.vtd;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.texture.NativeImage;
import me.bymartrixx.vtd.data.DownloadPackRequestData;
import me.bymartrixx.vtd.data.DownloadPackResponseData;
import me.bymartrixx.vtd.data.Pack;
import me.bymartrixx.vtd.data.RpCategories;
import me.bymartrixx.vtd.data.SharePackRequestData;
import me.bymartrixx.vtd.data.SharePackResponseData;
import me.bymartrixx.vtd.util.Constants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.resource.ResourceIoSupplier;
import net.minecraft.resource.pack.PackProfile;
import net.minecraft.resource.pack.ResourcePack;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

public class VTDMod implements ClientModInitializer {
    // DEBUG
    public static final boolean USE_LOCAL_CATEGORIES = false;

    private static final ThreadFactory DOWNLOAD_THREAD_FACTORY = new ThreadFactoryBuilder()
            .setNameFormat("VT Download %d").build();
    private static final ExecutorService DOWNLOAD_EXECUTOR = Executors.newCachedThreadPool(DOWNLOAD_THREAD_FACTORY);
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(DownloadPackRequestData.class, new DownloadPackRequestData.Serializer())
            .registerTypeAdapter(SharePackRequestData.class, new SharePackRequestData.Serializer())
            .create();
    public static final String MOD_NAME = "VTDownloader";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
    public static final String BASE_URL = "https://vanillatweaks.net";
    public static final String MOD_ID = "vt_downloader";

    public static final String VT_VERSION;
    public static final String VERSION;

    private static HttpClient httpClient;

    public static RpCategories rpCategories;

    static {
        String version = "2.4.0";
        String vtVersion = "1.21";

        Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(MOD_ID);
        if (container.isPresent()) {
            vtVersion = container.get().getMetadata().getCustomValue("vt_version").getAsString();
        }

        VERSION = version;
        VT_VERSION = vtVersion;
    }

    private static HttpClient getClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
        return httpClient;
    }

    private static String getResourceUri(String resource) {
        resource = !resource.startsWith("/") ? "/" + resource : resource;
        return BASE_URL + resource;
    }

    @Contract("_ -> new")
    private static HttpRequest createHttpGet(String resource) {
        return HttpRequest.newBuilder()
                .uri(URI.create(getResourceUri(resource)))
                .header("User-Agent", "VTDownloader v" + VERSION)
                .GET()
                .build();
    }

    private static String buildFormBody(List<String[]> params) {
        StringBuilder sb = new StringBuilder();
        for (String[] param : params) {
            if (sb.length() > 0) sb.append("&");
            sb.append(URLEncoder.encode(param[0], StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(URLEncoder.encode(param[1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    @Contract("_ -> new")
    private static HttpRequest createHttpPost(String resource, String formBody) {
        return HttpRequest.newBuilder()
                .uri(URI.create(getResourceUri(resource)))
                .header("User-Agent", "VTDownloader v" + VERSION)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
    }

    public static HttpResponse<InputStream> executeRequest(HttpRequest request) throws IOException {
        try {
            return getClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }

    public static void loadRpCategories() {
        try {
            RpCategories categories = null;
            String file = System.getProperty("vtd.debug.rpCategoriesFile");
            if (USE_LOCAL_CATEGORIES && file != null) {
                try (BufferedReader reader = Files.newBufferedReader(Path.of(file))) {
                    categories = GSON.fromJson(reader, RpCategories.class);
                } catch (IOException e) {
                    LOGGER.warn("Failed to load debug categories", e);
                    categories = null;
                }
            }

            if (categories == null) {
                HttpResponse<InputStream> response = executeRequest(createHttpGet("/assets/resources/json/" + VT_VERSION + "/rpcategories.json"));
                try (InputStream stream = new BufferedInputStream(response.body())) {
                    categories = GSON.fromJson(new InputStreamReader(stream), RpCategories.class);
                }
            }

            if (categories == null) {
                LOGGER.error("Failed to load resource pack categories");
                return;
            }

            rpCategories = categories;
            LOGGER.info("Loaded {} resource pack categories", rpCategories.getCategories().size());
        } catch (Exception e) {
            LOGGER.error("Failed to load resource pack categories", e);
        }
    }

    public static CompletableFuture<Boolean> executePackDownload(
            DownloadPackRequestData requestData, Consumer<Float> progressCallback,
            Path downloadPath, @Nullable String userFileName) {
        LOGGER.debug("Downloading resource packs: {}", GSON.toJson(requestData));

        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String[]> params = new ArrayList<>();
                params.add(new String[]{"version", VT_VERSION});
                params.add(new String[]{"packs", GSON.toJson(requestData)});
                HttpRequest request = createHttpPost("/assets/server/zipresourcepacks.php", buildFormBody(params));
                return executeRequest(request);
            } catch (IOException e) {
                throw new RuntimeException("Failed to execute pack zipping request", e);
            }
        }, DOWNLOAD_EXECUTOR).thenApplyAsync(response -> {
            progressCallback.accept(0.1F);
            int code = response.statusCode();
            if (code / 100 != 2) {
                throw new IllegalStateException("Pack zipping request returned status code " + code);
            }

            try (InputStream stream = new BufferedInputStream(response.body())) {
                return GSON.fromJson(new InputStreamReader(stream), DownloadPackResponseData.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read pack zipping response", e);
            }
        }, DOWNLOAD_EXECUTOR).thenApplyAsync(data -> {
            progressCallback.accept(0.3F);
            String fileName = userFileName != null ? userFileName + ".zip" : data.getFileName();

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(getResourceUri(data.getLink())))
                        .header("User-Agent", "VTDownloader v" + VERSION)
                        .timeout(Duration.ofSeconds(4))
                        .GET()
                        .build();

                return new Pair<>(fileName, executeRequest(request));
            } catch (IOException e) {
                throw new RuntimeException("Failed to execute pack download request", e);
            }
        }, DOWNLOAD_EXECUTOR).thenApplyAsync(data -> {
            progressCallback.accept(0.4F);

            HttpResponse<InputStream> response = data.getRight();
            int code = response.statusCode();
            if (code / 100 != 2) {
                throw new IllegalStateException("Pack download request returned status code " + code);
            }

            String fileName = data.getLeft().trim();
            try (InputStream stream = new BufferedInputStream(response.body())) {
                return Files.copy(stream, downloadPath.resolve(fileName), StandardCopyOption.REPLACE_EXISTING) > 0;
            } catch (IOException e) {
                throw new RuntimeException("Failed to read pack download response", e);
            }
        }, DOWNLOAD_EXECUTOR);
    }

    public static CompletableFuture<String> executeShare(SharePackRequestData requestData) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String[]> params = Collections.singletonList(
                        new String[]{"data", GSON.toJson(requestData)});
                HttpRequest request = createHttpPost("/assets/server/sharecode.php", buildFormBody(params));
                return executeRequest(request);
            } catch (IOException e) {
                throw new RuntimeException("Failed to execute pack share request", e);
            }
        }).thenApplyAsync(response -> {
            int code = response.statusCode();
            if (code / 100 != 2) {
                throw new IllegalStateException("Pack share request returned status code " + code);
            }

            try (InputStream stream = new BufferedInputStream(response.body())) {
                return GSON.fromJson(new InputStreamReader(stream), SharePackResponseData.class);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read pack share response", e);
            }
        }).thenApplyAsync(data -> {
            if (data.getResult().equals("error")) {
                throw new IllegalStateException("There was an error sharing the pack");
            }
            return data.getCode();
        });
    }

    public static CompletableFuture<NativeImage> downloadIcon(Pack pack) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = createHttpGet(
                        String.format("/assets/resources/icons/resourcepacks/%s/%s.png", VT_VERSION, pack.getIcon()));
                return executeRequest(request);
            } catch (IOException e) {
                throw new RuntimeException("Failed to execute icon download request", e);
            }
        }, DOWNLOAD_EXECUTOR).thenApplyAsync(response -> {
            int code = response.statusCode();
            if (code / 100 != 2) {
                throw new IllegalStateException("Icon download request returned status code " + code);
            }

            try (InputStream stream = response.body()) {
                return NativeImage.read(stream);
            } catch (IOException e) {
                throw new RuntimeException("Failed to read icon download response", e);
            }
        });
    }

    @Contract("_ -> new")
    public static Identifier getIconId(Pack pack) {
        return Identifier.of(MOD_ID, pack.getId().toLowerCase(Locale.ROOT));
    }

    public static CompletableFuture<List<String>> readResourcePackData(PackProfile profile) {
        return CompletableFuture.supplyAsync(() -> {
            try (ResourcePack resourcePack = profile.createPack()) {
                ResourceIoSupplier<InputStream> fileStream = resourcePack.openRoot(Constants.SELECTED_PACKS_FILE);
                try (InputStream stream = fileStream != null ? fileStream.get() : null) {
                    if (stream != null) {
                        return readSelectedPacks(new BufferedReader(new InputStreamReader(stream)));
                    } else {
                        return Collections.emptyList();
                    }
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public static List<String> readSelectedPacks(BufferedReader reader) throws IOException {
        List<String> selectedPacks = new ArrayList<>();

        int count = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            if (count == 0 && line.trim().equals(Constants.SELECTED_PACKS_FILE_HEADER)) {
                count++;
                continue;
            } else if (count == 1 && line.trim().startsWith("Version:")) {
                if (!line.substring(8).trim().equals(VT_VERSION)) {
                    throw new IllegalArgumentException("Unsupported pack version");
                }
                count++;
                continue;
            } else if (count == 2 && line.trim().startsWith("Packs:")) {
                count++;
                continue;
            } else if (count > 2) {
                if (!line.isBlank()) {
                    selectedPacks.add(line.trim());
                }

                count++;
                continue;
            }

            throw new IllegalStateException("Invalid selected packs file");
        }

        return selectedPacks;
    }

    @Override
    public void onInitializeClient() {
        LOGGER.info("VTDownloader {}, using Vanilla Tweaks {}", VERSION, VT_VERSION);
        loadRpCategories();
    }
}
