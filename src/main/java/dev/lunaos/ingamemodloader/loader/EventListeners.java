package dev.lunaos.ingamemodloader.loader;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.impl.base.event.EventFactoryImpl;
import net.minecraft.resources.Identifier;

/**
 * Removes listeners from every Fabric API event and adds them back later. Fabric API has no way to
 * unregister a listener, so this works on the fields of its event implementation.
 */
final class EventListeners {
	record Removed(Event<Object> event, Identifier phase, Object listener) {
	}

	private EventListeners() {
	}

	@SuppressWarnings("unchecked")
	static List<Removed> remove(Predicate<Object> shouldRemove) throws ReflectiveOperationException {
		Class<?> eventClass = Class.forName("net.fabricmc.fabric.impl.base.event.ArrayBackedEvent");
		Class<?> phaseClass = Class.forName("net.fabricmc.fabric.impl.base.event.EventPhaseData");
		Field lockField = accessible(eventClass.getDeclaredField("lock"));
		Field phasesField = accessible(eventClass.getDeclaredField("sortedPhases"));
		Field listenersField = accessible(phaseClass.getDeclaredField("listeners"));
		Field phaseIdField = accessible(phaseClass.getDeclaredField("id"));
		Method rebuildInvoker = eventClass.getDeclaredMethod("rebuildInvoker", int.class);
		rebuildInvoker.setAccessible(true);
		Set<?> events = (Set<?>) accessible(EventFactoryImpl.class.getDeclaredField("ARRAY_BACKED_EVENTS")).get(null);
		List<Removed> removed = new ArrayList<>();

		for (Object event : events) {
			// Registering a listener holds the same lock, so this cannot interleave with a registration.
			synchronized (lockField.get(event)) {
				int remaining = 0;
				boolean changed = false;

				for (Object phase : (List<?>) phasesField.get(event)) {
					Object[] listeners = (Object[]) listenersField.get(phase);
					List<Object> kept = new ArrayList<>(listeners.length);

					for (Object listener : listeners) {
						if (shouldRemove.test(listener)) {
							removed.add(new Removed((Event<Object>) event, (Identifier) phaseIdField.get(phase), listener));
						} else {
							kept.add(listener);
						}
					}

					if (kept.size() != listeners.length) {
						listenersField.set(phase, kept.toArray((Object[]) Array.newInstance(listeners.getClass().getComponentType(), 0)));
						changed = true;
					}

					remaining += kept.size();
				}

				if (changed) rebuildInvoker.invoke(event, remaining);
			}
		}

		return removed;
	}

	static void restore(List<Removed> removed) {
		for (Removed entry : removed) {
			entry.event().register(entry.phase(), entry.listener());
		}
	}

	private static Field accessible(Field field) {
		field.setAccessible(true);
		return field;
	}
}
