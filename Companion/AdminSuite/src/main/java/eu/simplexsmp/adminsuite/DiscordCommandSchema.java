package eu.simplexsmp.adminsuite;

import java.util.*;

/** Discord's per-command 8,000 character budget includes every choice name/value. */
final class DiscordCommandSchema {
    static final int LIMIT = 8000;
    static int size(Object raw) {
        if (raw instanceof List<?> list) return list.stream().mapToInt(DiscordCommandSchema::size).sum();
        if (!(raw instanceof Map<?,?> node)) return 0;
        int size = 0;
        for (String key : List.of("name", "description", "value")) {
            int longest = node.get(key) instanceof String text ? text.codePointCount(0, text.length()) : 0;
            if (node.get(key + "_localizations") instanceof Map<?,?> locales)
                for (Object value : locales.values()) if (value instanceof String text)
                    longest = Math.max(longest, text.codePointCount(0, text.length()));
            size += longest;
        }
        return size + size(node.get("options")) + size(node.get("choices"));
    }
    static void compactDescriptions(Map<String,Object> command) {
        List<Map<String,Object>> nodes = new ArrayList<>();
        collect(command, nodes);
        // Keep routing names, option types, required flags and choice values intact.
        while (size(command) > 7800) {
            Map<String,Object> longest = nodes.stream().filter(n -> !n.containsKey("description_localizations"))
                    .filter(n -> n.get("description") instanceof String s && s.length() > 16)
                    .max(Comparator.comparingInt(n -> ((String)n.get("description")).length())).orElseThrow(
                            () -> new IllegalArgumentException("Command cannot fit Discord's character budget: " + command.get("name")));
            String text = (String)longest.get("description");
            int end = Math.max(16, text.length() - Math.max(8, size(command) - 7800));
            int space = text.lastIndexOf(' ', end);
            if (space >= 12) end = space;
            longest.put("description", text.substring(0, end).stripTrailing());
        }
    }
    @SuppressWarnings("unchecked") private static void collect(Map<String,Object> node, List<Map<String,Object>> nodes) {
        nodes.add(node);
        if (node.get("options") instanceof List<?> children)
            for (Object raw : children) collect((Map<String,Object>)raw, nodes);
    }
    static void validate(List<Object> commands) {
        Set<String> roots = new HashSet<>();
        for (Object raw : commands) {
            if (!(raw instanceof Map<?,?> command)) throw new IllegalArgumentException("Invalid Discord command");
            validateText(command, roots);
            if (size(command) > LIMIT) throw new IllegalArgumentException("Discord command /" + command.get("name") + " exceeds 8,000 characters (" + size(command) + ")");
            validateOptions(command.get("options"), 0);
        }
    }
    private static void validateText(Map<?,?> node, Set<String> names) {
        String name = Objects.toString(node.get("name"), ""), description = Objects.toString(node.get("description"), "");
        if (!name.matches("[-_\\p{L}\\p{N}]{1,32}") || !name.equals(name.toLowerCase(Locale.ROOT)) || !names.add(name))
            throw new IllegalArgumentException("Invalid or duplicate Discord name: " + name);
        if (description.isBlank() || description.codePointCount(0, description.length()) > 100)
            throw new IllegalArgumentException("Invalid Discord description: " + name);
    }
    private static void validateOptions(Object raw, int depth) {
        if (raw == null) return;
        if (!(raw instanceof List<?> options) || options.size() > 25 || depth > 2) throw new IllegalArgumentException("Invalid Discord option topology");
        Set<String> names = new HashSet<>(); boolean optional = false, branches = false, values = false;
        for (Object item : options) {
            if (!(item instanceof Map<?,?> node)) throw new IllegalArgumentException("Invalid Discord option");
            validateText(node, names);
            int type = node.get("type") instanceof Number n ? n.intValue() : 0;
            if (type < 1 || type > 11 || depth == 2 && type <= 2 || depth == 1 && type == 2) throw new IllegalArgumentException("Invalid Discord option type");
            if (type <= 2) { branches = true; if (node.containsKey("required")) throw new IllegalArgumentException("Subcommand cannot be required"); }
            else { values = true; boolean required = Boolean.TRUE.equals(node.get("required")); if (optional && required) throw new IllegalArgumentException("Required Discord option follows optional option"); optional |= !required; if (node.containsKey("options")) throw new IllegalArgumentException("Parameter cannot contain options"); }
            if (branches && values) throw new IllegalArgumentException("Cannot mix Discord subcommands and parameters");
            if (node.get("choices") instanceof List<?> choices) {
                if (choices.size() > 25 || !Set.of(3,4,10).contains(type) || Boolean.TRUE.equals(node.get("autocomplete"))) throw new IllegalArgumentException("Invalid Discord choices");
                for (Object choice : choices) if (!(choice instanceof Map<?,?> c) || Objects.toString(c.get("name"), "").isBlank() || Objects.toString(c.get("name"), "").length() > 100 || c.get("value") instanceof String s && s.length() > 100) throw new IllegalArgumentException("Invalid Discord choice");
            }
            validateOptions(node.get("options"), depth + 1);
        }
    }
}
