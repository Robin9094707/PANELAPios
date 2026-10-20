package eu.simplexsmp.adminsuite;

import java.util.Objects;
import java.util.function.BiConsumer;

/** One outcome per admin action, including nested saves and form callbacks. */
final class MenuFeedback {
    private final BiConsumer<String, Boolean> sound;
    private final ThreadLocal<Scope> current = new ThreadLocal<>();
    private static final class Scope {
        final String actor;
        boolean success, failure;
        Scope(String actor) { this.actor = actor; }
    }
    MenuFeedback(BiConsumer<String, Boolean> sound) { this.sound = sound; }
    void run(String actor, Runnable action) {
        Scope previous = current.get();
        if (previous != null && Objects.equals(previous.actor, actor)) {
            try { action.run(); } catch (RuntimeException failure) { previous.failure = true; throw failure; }
            return;
        }
        Scope scope = new Scope(actor);
        current.set(scope);
        try { action.run(); }
        catch (RuntimeException failure) { scope.failure = true; throw failure; }
        finally {
            if (previous == null) current.remove(); else current.set(previous);
            if (scope.failure || scope.success) sound.accept(actor, !scope.failure);
        }
    }
    void success(String actor) { outcome(actor, true); }
    void failure(String actor) { outcome(actor, false); }
    private void outcome(String actor, boolean success) {
        if (actor == null || actor.isBlank()) return;
        Scope scope = current.get();
        if (scope != null && Objects.equals(scope.actor, actor)) {
            if (success) scope.success = true; else scope.failure = true;
        } else sound.accept(actor, success);
    }
}
