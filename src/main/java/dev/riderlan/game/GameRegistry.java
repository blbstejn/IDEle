package dev.riderlan.game;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.regex.Pattern;

/**
 * Installed games, discovered through {@code META-INF/services/dev.riderlan.game.GameDefinition}.
 * A game that fails to load or declares invalid metadata is skipped and reported in
 * {@link #problems()}; it never prevents the rest of the plugin from working.
 */
public final class GameRegistry {
    private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,32}");
    private static volatile GameRegistry installed;

    private final Map<String, GameDefinition> games = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();

    private GameRegistry(Iterable<GameDefinition> candidates, List<String> loadProblems) {
        problems.addAll(loadProblems);
        List<GameDefinition> sorted = new ArrayList<>();
        for (GameDefinition game : candidates) {
            String problem = validate(game);
            if (problem != null) problems.add(problem);
            else if (sorted.stream().anyMatch(g -> g.id().equals(game.id()))) {
                problems.add("Duplicate game id '" + game.id() + "' in " + game.getClass().getName());
            } else sorted.add(game);
        }
        sorted.sort(Comparator.comparing(GameDefinition::displayName, String.CASE_INSENSITIVE_ORDER));
        for (GameDefinition game : sorted) games.put(game.id(), game);
    }

    /** Games bundled in this plugin build. */
    public static GameRegistry installed() {
        GameRegistry current = installed;
        if (current == null) {
            synchronized (GameRegistry.class) {
                if (installed == null) installed = load();
                current = installed;
            }
        }
        return current;
    }

    /** An explicit set of games, for tests. */
    public static GameRegistry of(Collection<? extends GameDefinition> definitions) {
        return new GameRegistry(List.copyOf(definitions), List.of());
    }

    private static GameRegistry load() {
        // The plugin's own class loader: IntelliJ does not put plugin jars on the context loader.
        ServiceLoader<GameDefinition> loader =
                ServiceLoader.load(GameDefinition.class, GameRegistry.class.getClassLoader());
        List<GameDefinition> found = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Iterator<GameDefinition> iterator = loader.iterator();
        while (true) {
            try {
                if (!iterator.hasNext()) break;
                found.add(iterator.next());
            } catch (ServiceConfigurationError | RuntimeException | LinkageError broken) {
                failures.add("Could not load a game: " + broken.getMessage());
            }
        }
        GameRegistry registry = new GameRegistry(found, failures);
        for (String problem : registry.problems) System.err.println("IDEle: " + problem);
        return registry;
    }

    private static String validate(GameDefinition game) {
        String type = game.getClass().getName();
        try {
            if (game.id() == null || !ID.matcher(game.id()).matches()) return "Invalid game id in " + type;
            if (game.displayName() == null || game.displayName().isBlank()) return "Missing display name in " + type;
            int min = game.minPlayers(), max = game.maxPlayers();
            if (min < 1 || max > 2 || min > max) return "Unsupported player counts " + min + "–" + max + " in " + type;
            return null;
        } catch (RuntimeException broken) {
            return "Invalid game " + type + ": " + broken.getMessage();
        }
    }

    public List<GameDefinition> all() { return List.copyOf(games.values()); }
    public List<String> ids() { return List.copyOf(games.keySet()); }
    public GameDefinition find(String id) { return games.get(id); }
    public boolean contains(String id) { return games.containsKey(id); }
    public List<String> problems() { return List.copyOf(problems); }

    /** Display name of an id, or the id itself when this build does not have the game. */
    public String name(String id) {
        GameDefinition game = games.get(id);
        return game == null ? id : game.displayName();
    }

    /** Whether the id has a valid format; used to validate ids announced by other machines. */
    public static boolean validId(String id) { return id != null && ID.matcher(id).matches(); }
}
