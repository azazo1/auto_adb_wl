package com.azazo1.auto_adb_wl_client.lndplayground;

import io.github.azazo1.lnd.AddressSelection;
import io.github.azazo1.lnd.AnnounceHandle;
import io.github.azazo1.lnd.AnnounceSpec;
import io.github.azazo1.lnd.Client;
import io.github.azazo1.lnd.DiscoveredNode;
import io.github.azazo1.lnd.DiscoveryEvent;
import io.github.azazo1.lnd.DiscoveryFilter;
import io.github.azazo1.lnd.LndException;
import io.github.azazo1.lnd.LeaseInfo;
import io.github.azazo1.lnd.WatchHandle;
import java.util.AbstractMap;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public final class Main {
    private static final String ENV_LND_BASE_URL = "AUTO_ADB_WL_LND_BASE_URL";
    private static final String ENV_LND_BEARER_TOKEN = "AUTO_ADB_WL_LND_BEARER_TOKEN";
    private static final String ENV_LND_DISCOVERY_DOMAIN = "AUTO_ADB_WL_LND_DISCOVERY_DOMAIN";
    private static final String ENV_LND_SERVICE_NAME = "AUTO_ADB_WL_LND_SERVICE_NAME";
    private static final DateTimeFormatter TIME_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withLocale(Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    public static void main(String[] args) throws Exception {
        Config.printDotEnvLoadSummary();
        if (args.length == 0 || hasFlag(args, "--help") || hasFlag(args, "-h")) {
            printUsage();
            return;
        }

        String command = args[0];
        String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);
        Config config = Config.parse(commandArgs);

        switch (command) {
            case "scopes":
                runScopes(config);
                return;
            case "discover":
                runDiscover(config);
                return;
            case "watch":
                runWatch(config);
                return;
            case "announce-once":
                runAnnounceOnce(config);
                return;
            case "announce-loop":
                runAnnounceLoop(config);
                return;
            default:
                throw new IllegalArgumentException("unknown command: " + command);
        }
    }

    private static void runScopes(Config config) throws Exception {
        Client client = buildClient(config);
        printConfigSummary("scopes", config, null, client);
        List<String> scopes = await(client.listReachabilityScopesAsync());
        System.out.println("local reachability scopes:");
        printList(scopes, "  ");
    }

    private static void runDiscover(Config config) throws Exception {
        Client client = buildClient(config);
        DiscoveryFilter filter = buildFilter(config);
        printConfigSummary("discover", config, filter, client);
        List<String> localScopes = safeListReachabilityScopes(client);
        System.out.println("local reachability scopes:");
        printList(localScopes, "  ");

        List<DiscoveredNode> plainNodes = tryDiscover(client, filter.copy(), false);
        List<DiscoveredNode> autoNodes = tryDiscover(client, filter.copy(), true);
        printNodeSet("plain discover", plainNodes);
        printNodeSet("auto-scope discover", autoNodes);
    }

    private static void runWatch(Config config) throws Exception {
        Client client = buildClient(config);
        DiscoveryFilter filter = buildFilter(config);
        printConfigSummary("watch", config, filter, client);
        List<String> localScopes = safeListReachabilityScopes(client);
        System.out.println("local reachability scopes:");
        printList(localScopes, "  ");
        System.out.println("watch mode: press Enter to stop");

        WatchHandle handle = startWatch(client, filter, config.autoScopeOverlap);
        try {
            waitForEnter();
        } finally {
            handle.close();
            LndException error = handle.awaitStopped();
            if (error == null) {
                System.out.println("watch stopped cleanly");
            } else {
                System.out.println("watch stopped with error: " + error.getMessage());
                error.printStackTrace(System.out);
            }
        }
    }

    private static void runAnnounceOnce(Config config) throws Exception {
        Client client = buildClient(config);
        AnnounceSpec spec = buildAnnounceSpec(config);
        printConfigSummary("announce-once", config, null, client);
        printAnnounceSpec(spec);
        System.out.println("resolved announce addrs:");
        printList(client.resolveAnnounceAddrs(spec), "  ");
        System.out.println("resolved announce scopes:");
        printList(client.resolveReachabilityScopes(spec), "  ");
        DiscoveredNode node = client.announceOnce(spec);
        System.out.println("announce succeeded:");
        printNode(node, "  ");
    }

    private static void runAnnounceLoop(Config config) throws Exception {
        Client client = buildClient(config);
        AnnounceSpec spec = buildAnnounceSpec(config);
        printConfigSummary("announce-loop", config, null, client);
        printAnnounceSpec(spec);
        System.out.println("resolved announce addrs:");
        printList(client.resolveAnnounceAddrs(spec), "  ");
        System.out.println("resolved announce scopes:");
        printList(client.resolveReachabilityScopes(spec), "  ");
        System.out.println("announce loop mode: press Enter to stop");

        AnnounceHandle handle = client.announce(spec);
        try {
            waitForEnter();
        } finally {
            handle.close();
            LndException error = handle.awaitStopped();
            if (error == null) {
                System.out.println("announce loop stopped cleanly");
            } else {
                System.out.println("announce loop stopped with error: " + error.getMessage());
                error.printStackTrace(System.out);
            }
        }
    }

    private static List<DiscoveredNode> tryDiscover(Client client, DiscoveryFilter filter, boolean autoScopeOverlap) {
        try {
            List<DiscoveredNode> nodes = autoScopeOverlap
                ? await(client.discoverWithAutoScopeOverlapAsync(filter))
                : await(client.discoverAsync(filter));
            System.out.println((autoScopeOverlap ? "auto-scope" : "plain") + " discover succeeded: nodes=" + nodes.size());
            return nodes;
        } catch (Exception error) {
            System.out.println((autoScopeOverlap ? "auto-scope" : "plain") + " discover failed: " + error.getMessage());
            error.printStackTrace(System.out);
            return Collections.emptyList();
        }
    }

    private static WatchHandle startWatch(Client client, DiscoveryFilter filter, boolean autoScopeOverlap) throws LndException {
        if (autoScopeOverlap) {
            System.out.println("starting watch with auto scope overlap");
            return await(client.watchWithAutoScopeOverlapAsync(filter, envelope -> printWatchEvent("event", envelope.getCursor(), envelope.getEvent())));
        }
        System.out.println("starting plain watch");
        return await(client.watchAsync(filter, envelope -> printWatchEvent("event", envelope.getCursor(), envelope.getEvent())));
    }

    private static void printWatchEvent(String label, Long cursor, DiscoveryEvent event) {
        System.out.println("[" + now() + "] " + label + ": cursor=" + cursor + ", type=" + event.getType().wireName());
        if (event.getType() == DiscoveryEvent.Type.SNAPSHOT) {
            System.out.println("  snapshot nodes=" + event.getNodes().size());
            for (DiscoveredNode node : event.getNodes()) {
                printNode(node, "    ");
            }
            return;
        }
        if (event.getNode() != null) {
            printNode(event.getNode(), "  ");
        }
    }

    private static Client buildClient(Config config) {
        Client client = new Client(config.serverUrl, config.bearerToken);
        client.setTimeoutMillis(config.timeoutMillis);
        client.setReconnectBackoffMillis(config.reconnectBackoffMinMillis, config.reconnectBackoffMaxMillis);
        applySelection(client, config);
        return client;
    }

    private static void applySelection(Client client, Config config) {
        client.setIncludePrivateIpv4(config.includePrivateIpv4);
        client.setIncludeLoopback(config.includeLoopback);
        client.setIncludeLinkLocalIpv4(config.includeLinkLocalIpv4);
        client.setIncludeIpv6(config.includeIpv6);
        client.clearInterfaceFilters();
        for (String name : config.interfaceAllowlist) {
            client.enableInterface(name);
        }
        for (String name : config.interfaceDenylist) {
            client.disableInterface(name);
        }
    }

    private static DiscoveryFilter buildFilter(Config config) {
        DiscoveryFilter filter = new DiscoveryFilter();
        if (!config.discoveryDomain.isEmpty()) {
            filter.withDiscoveryDomain(config.discoveryDomain);
        }
        if (!config.service.isEmpty()) {
            filter.withService(config.service);
        }
        for (String tag : config.tags) {
            filter.addTag(tag);
        }
        if (!config.filterScopes.isEmpty()) {
            filter.withReachabilityScopes(config.filterScopes);
        }
        return filter;
    }

    private static AnnounceSpec buildAnnounceSpec(Config config) {
        if (config.nodeId.isEmpty()) {
            throw new IllegalArgumentException("--node-id is required for announce commands");
        }
        if (config.displayName.isEmpty()) {
            throw new IllegalArgumentException("--display-name is required for announce commands");
        }
        if (config.port <= 0) {
            throw new IllegalArgumentException("--port must be greater than 0 for announce commands");
        }
        AnnounceSpec spec = new AnnounceSpec(config.nodeId, config.service, config.displayName, config.port)
            .withAutoLanAddrs(config.autoLanAddrs)
            .withAutoReachabilityScopes(config.autoReachabilityScopes)
            .withTtlSecs(config.ttlSecs);
        if (!config.discoveryDomain.isEmpty()) {
            spec.withDiscoveryDomain(config.discoveryDomain);
        }
        if (!config.announceLanAddrs.isEmpty()) {
            spec.withLanAddrs(config.announceLanAddrs);
        }
        if (!config.announceScopes.isEmpty()) {
            spec.withReachabilityScopes(config.announceScopes);
        }
        for (String tag : config.tags) {
            spec.addTag(tag);
        }
        for (Map.Entry<String, String> entry : config.metadata.entrySet()) {
            spec.insertMetadata(entry.getKey(), entry.getValue());
        }
        AddressSelection selection = new AddressSelection()
            .withPrivateIpv4(config.includePrivateIpv4)
            .withLoopback(config.includeLoopback)
            .withLinkLocalIpv4(config.includeLinkLocalIpv4)
            .withIpv6(config.includeIpv6);
        for (String name : config.interfaceAllowlist) {
            selection.enableInterface(name);
        }
        for (String name : config.interfaceDenylist) {
            selection.disableInterface(name);
        }
        spec.withAddressSelection(selection);
        return spec;
    }

    private static void printConfigSummary(String command, Config config, DiscoveryFilter filter, Client client) {
        System.out.println("command: " + command);
        System.out.println("serverUrl: " + client.getBaseUrl());
        System.out.println("bearerToken: " + (config.bearerToken.isEmpty() ? "<empty>" : "<set>"));
        System.out.println("timeoutMillis: " + config.timeoutMillis);
        System.out.println(
            "addressSelection: includePrivateIpv4=" + config.includePrivateIpv4
                + ", includeLoopback=" + config.includeLoopback
                + ", includeLinkLocalIpv4=" + config.includeLinkLocalIpv4
                + ", includeIpv6=" + config.includeIpv6
                + ", allow=" + config.interfaceAllowlist
                + ", deny=" + config.interfaceDenylist
        );
        if (filter != null) {
            System.out.println("filter:");
            System.out.println("  discoveryDomain: " + printable(filter.getDiscoveryDomain()));
            System.out.println("  service: " + printable(filter.getService()));
            System.out.println("  tags: " + filter.getTags());
            System.out.println("  filterScopes: " + filter.getReachabilityScopes());
            System.out.println("  autoScopeOverlap: " + config.autoScopeOverlap);
        }
    }

    private static void printAnnounceSpec(AnnounceSpec spec) {
        System.out.println("announce spec:");
        System.out.println("  nodeId: " + spec.getNodeId());
        System.out.println("  displayName: " + spec.getDisplayName());
        System.out.println("  service: " + spec.getService());
        System.out.println("  port: " + spec.getPort());
        System.out.println("  discoveryDomain: " + printable(spec.getDiscoveryDomain()));
        System.out.println("  lanAddrs: " + spec.getLanAddrs());
        System.out.println("  autoLanAddrs: " + spec.isAutoLanAddrs());
        System.out.println("  reachabilityScopes: " + spec.getReachabilityScopes());
        System.out.println("  autoReachabilityScopes: " + spec.isAutoReachabilityScopes());
        System.out.println("  tags: " + spec.getTags());
        System.out.println("  metadata: " + spec.getMetadata());
        System.out.println("  ttlSecs: " + spec.getTtlSecs());
    }

    private static void printNodeSet(String label, List<DiscoveredNode> nodes) {
        System.out.println(label + ":");
        if (nodes.isEmpty()) {
            System.out.println("  <empty>");
            return;
        }
        for (DiscoveredNode node : nodes) {
            printNode(node, "  ");
        }
    }

    private static void printNode(DiscoveredNode node, String indent) {
        System.out.println(indent + "nodeId: " + node.getNodeId());
        System.out.println(indent + "displayName: " + node.getDisplayName());
        System.out.println(indent + "service: " + node.getService());
        System.out.println(indent + "port: " + node.getPort());
        System.out.println(indent + "discoveryDomain: " + printable(node.getDiscoveryDomain()));
        System.out.println(indent + "lanAddrs: " + node.getLanAddrs());
        System.out.println(indent + "reachabilityScopes: " + node.getReachabilityScopes());
        System.out.println(indent + "tags: " + node.getTags());
        System.out.println(indent + "metadata: " + node.getMetadata());
        printLease(node.getLease(), indent);
    }

    private static void printLease(LeaseInfo lease, String indent) {
        if (lease == null) {
            System.out.println(indent + "lease: <none>");
            return;
        }
        System.out.println(indent + "lease.revision: " + lease.getRevision());
        System.out.println(indent + "lease.ttlSecs: " + lease.getTtlSecs());
        System.out.println(indent + "lease.lastSeen: " + formatTime(lease.getLastSeenUnixMs()));
        System.out.println(indent + "lease.expiresAt: " + formatTime(lease.getExpiresAtUnixMs()));
    }

    private static void printList(List<String> values, String indent) {
        if (values.isEmpty()) {
            System.out.println(indent + "<empty>");
            return;
        }
        for (String value : values) {
            System.out.println(indent + value);
        }
    }

    private static String printable(String value) {
        return value == null || value.isEmpty() ? "<empty>" : value;
    }

    private static List<String> safeListReachabilityScopes(Client client) {
        try {
            return await(client.listReachabilityScopesAsync());
        } catch (Exception error) {
            System.out.println("failed to list local reachability scopes: " + error.getMessage());
            error.printStackTrace(System.out);
            return Collections.emptyList();
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws LndException {
        try {
            return future.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new LndException("async call interrupted", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof LndException) {
                throw (LndException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new LndException("async call failed", cause == null ? error : cause);
        }
    }

    private static String now() {
        return TIME_FORMATTER.format(Instant.now());
    }

    private static String formatTime(long epochMillis) {
        return TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis)) + " (" + epochMillis + ")";
    }

    private static void waitForEnter() throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        reader.readLine();
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  ./gradlew.bat :lnd-playground:run --args=\"<command> [options]\"");
        System.out.println("  values are loaded from .env by default when present, and command line options override them");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  scopes");
        System.out.println("  discover");
        System.out.println("  watch");
        System.out.println("  announce-once");
        System.out.println("  announce-loop");
        System.out.println();
        System.out.println("Common options:");
        System.out.println("  --server-url <url>                default: http://127.0.0.1:8765");
        System.out.println("  --bearer-token <token>            default: empty");
        System.out.println("  --service <name>                  default: _http._tcp");
        System.out.println("  --discovery-domain <value>        default: empty");
        System.out.println("  --tag <value>                     repeatable");
        System.out.println("  --scope <cidr>                    repeatable, adds explicit filter scopes");
        System.out.println("  --timeout-ms <millis>             default: 10000");
        System.out.println("  --no-auto-scope-overlap           use plain discover/watch instead of auto overlap");
        System.out.println("  --include-loopback");
        System.out.println("  --no-private-ipv4");
        System.out.println("  --include-link-local-ipv4");
        System.out.println("  --include-ipv6");
        System.out.println("  --allow-interface <name>          repeatable");
        System.out.println("  --deny-interface <name>           repeatable");
        System.out.println();
        System.out.println("Announce options:");
        System.out.println("  --node-id <value>");
        System.out.println("  --display-name <value>");
        System.out.println("  --port <number>");
        System.out.println("  --lan-addr <host:port>            repeatable");
        System.out.println("  --announce-scope <cidr>           repeatable");
        System.out.println("  --metadata <key=value>            repeatable");
        System.out.println("  --ttl-secs <seconds>              default: 30");
        System.out.println("  --no-auto-lan-addrs");
        System.out.println("  --no-auto-announce-scopes");
    }

    private static final class Config {
        private String serverUrl = defaultString(ENV_LND_BASE_URL, Client.DEFAULT_BASE_URL);
        private String bearerToken = defaultString(ENV_LND_BEARER_TOKEN, "");
        private String service = defaultString(ENV_LND_SERVICE_NAME, "_http._tcp");
        private String discoveryDomain = defaultString(ENV_LND_DISCOVERY_DOMAIN, "");
        private final List<String> tags = new ArrayList<String>();
        private final List<String> filterScopes = new ArrayList<String>();
        private int timeoutMillis = Client.DEFAULT_TIMEOUT_MILLIS;
        private long reconnectBackoffMinMillis = Client.DEFAULT_RECONNECT_BACKOFF_MIN_MILLIS;
        private long reconnectBackoffMaxMillis = Client.DEFAULT_RECONNECT_BACKOFF_MAX_MILLIS;
        private boolean autoScopeOverlap = true;
        private boolean includePrivateIpv4 = true;
        private boolean includeLoopback = false;
        private boolean includeLinkLocalIpv4 = false;
        private boolean includeIpv6 = false;
        private final List<String> interfaceAllowlist = new ArrayList<String>();
        private final List<String> interfaceDenylist = new ArrayList<String>();
        private String nodeId = "";
        private String displayName = "";
        private int port = 0;
        private final List<String> announceLanAddrs = new ArrayList<String>();
        private final List<String> announceScopes = new ArrayList<String>();
        private final Map<String, String> metadata = new LinkedHashMap<String, String>();
        private long ttlSecs = AnnounceSpec.DEFAULT_TTL_SECONDS;
        private boolean autoLanAddrs = true;
        private boolean autoReachabilityScopes = true;
        private static final File DOT_ENV_FILE = findDotEnvFile();
        private static final Map<String, String> DOT_ENV_VALUES = loadDotEnv(DOT_ENV_FILE);

        static Config parse(String[] args) {
            Config config = new Config();
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                switch (arg) {
                    case "--server-url":
                        config.serverUrl = requireValue(args, ++index, arg);
                        break;
                    case "--bearer-token":
                        config.bearerToken = requireValue(args, ++index, arg);
                        break;
                    case "--service":
                        config.service = requireValue(args, ++index, arg);
                        break;
                    case "--discovery-domain":
                        config.discoveryDomain = requireValue(args, ++index, arg);
                        break;
                    case "--tag":
                        config.tags.add(requireValue(args, ++index, arg));
                        break;
                    case "--scope":
                        config.filterScopes.add(requireValue(args, ++index, arg));
                        break;
                    case "--timeout-ms":
                        config.timeoutMillis = Integer.parseInt(requireValue(args, ++index, arg));
                        break;
                    case "--reconnect-backoff-min-ms":
                        config.reconnectBackoffMinMillis = Long.parseLong(requireValue(args, ++index, arg));
                        break;
                    case "--reconnect-backoff-max-ms":
                        config.reconnectBackoffMaxMillis = Long.parseLong(requireValue(args, ++index, arg));
                        break;
                    case "--no-auto-scope-overlap":
                        config.autoScopeOverlap = false;
                        break;
                    case "--include-loopback":
                        config.includeLoopback = true;
                        break;
                    case "--no-private-ipv4":
                        config.includePrivateIpv4 = false;
                        break;
                    case "--include-link-local-ipv4":
                        config.includeLinkLocalIpv4 = true;
                        break;
                    case "--include-ipv6":
                        config.includeIpv6 = true;
                        break;
                    case "--allow-interface":
                        config.interfaceAllowlist.add(requireValue(args, ++index, arg));
                        break;
                    case "--deny-interface":
                        config.interfaceDenylist.add(requireValue(args, ++index, arg));
                        break;
                    case "--node-id":
                        config.nodeId = requireValue(args, ++index, arg);
                        break;
                    case "--display-name":
                        config.displayName = requireValue(args, ++index, arg);
                        break;
                    case "--port":
                        config.port = Integer.parseInt(requireValue(args, ++index, arg));
                        break;
                    case "--lan-addr":
                        config.announceLanAddrs.add(requireValue(args, ++index, arg));
                        break;
                    case "--announce-scope":
                        config.announceScopes.add(requireValue(args, ++index, arg));
                        break;
                    case "--metadata":
                        String raw = requireValue(args, ++index, arg);
                        int separator = raw.indexOf('=');
                        if (separator <= 0) {
                            throw new IllegalArgumentException("--metadata expects key=value: " + raw);
                        }
                        config.metadata.put(raw.substring(0, separator), raw.substring(separator + 1));
                        break;
                    case "--ttl-secs":
                        config.ttlSecs = Long.parseLong(requireValue(args, ++index, arg));
                        break;
                    case "--no-auto-lan-addrs":
                        config.autoLanAddrs = false;
                        break;
                    case "--no-auto-announce-scopes":
                        config.autoReachabilityScopes = false;
                        break;
                    default:
                        throw new IllegalArgumentException("unknown option: " + arg);
                }
            }
            return config;
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("missing value for " + flag);
            }
            return args[index];
        }

        private static String defaultString(String key, String fallback) {
            String value = DOT_ENV_VALUES.get(key);
            if (value == null || value.trim().isEmpty()) {
                return fallback;
            }
            return value.trim();
        }

        private static void printDotEnvLoadSummary() {
            if (DOT_ENV_FILE == null) {
                System.out.println(".env load: file not found from current directory upward, using built-in defaults and command line overrides only");
                return;
            }
            System.out.println(".env load: loaded from " + DOT_ENV_FILE.getAbsolutePath());
            System.out.println(".env defaults:");
            printDotEnvValue(ENV_LND_BASE_URL, false);
            printDotEnvValue(ENV_LND_BEARER_TOKEN, true);
            printDotEnvValue(ENV_LND_DISCOVERY_DOMAIN, false);
            printDotEnvValue(ENV_LND_SERVICE_NAME, false);
        }

        private static void printDotEnvValue(String key, boolean secret) {
            String value = DOT_ENV_VALUES.get(key);
            if (value == null) {
                System.out.println("  " + key + "=<missing>");
                return;
            }
            if (value.trim().isEmpty()) {
                System.out.println("  " + key + "=<empty>");
                return;
            }
            if (secret) {
                System.out.println("  " + key + "=<set>");
                return;
            }
            System.out.println("  " + key + "=" + value.trim());
        }

        private static File findDotEnvFile() {
            File current = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
            while (current != null) {
                File candidate = new File(current, ".env");
                if (candidate.isFile()) {
                    return candidate;
                }
                current = current.getParentFile();
            }
            return null;
        }

        private static Map<String, String> loadDotEnv(File file) {
            if (file == null || !file.isFile()) {
                return Collections.emptyMap();
            }
            Map<String, String> values = new LinkedHashMap<>();
            try {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                for (String line : lines) {
                    Map.Entry<String, String> entry = parseDotEnvLine(line);
                    if (entry != null) {
                        values.put(entry.getKey(), entry.getValue());
                    }
                }
            } catch (IOException error) {
                throw new IllegalStateException("failed to read .env", error);
            }
            return values;
        }

        private static Map.Entry<String, String> parseDotEnvLine(String line) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return null;
            }
            String normalized = trimmed.startsWith("export ")
                ? trimmed.substring("export ".length()).trim()
                : trimmed;
            int separatorIndex = normalized.indexOf('=');
            if (separatorIndex <= 0) {
                return null;
            }
            String key = normalized.substring(0, separatorIndex).trim();
            if (key.isEmpty()) {
                return null;
            }
            String value = normalized.substring(separatorIndex + 1).trim();
            value = removeSurrounding(value, "\"");
            value = removeSurrounding(value, "'");
            return new AbstractMap.SimpleEntry<String, String>(key, value);
        }

        private static String removeSurrounding(String value, String delimiter) {
            if (value.length() >= delimiter.length() * 2
                && value.startsWith(delimiter)
                && value.endsWith(delimiter)) {
                return value.substring(delimiter.length(), value.length() - delimiter.length());
            }
            return value;
        }
    }
}
