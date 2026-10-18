package dev.lunaos.ingamemodloader.mixin;

import net.minecraft.core.Holder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Holder.Reference.class)
public interface HolderReferenceAccessor<T> {
	@Invoker("bindValue")
	void ingameModloader$bindValue(T value);
}
