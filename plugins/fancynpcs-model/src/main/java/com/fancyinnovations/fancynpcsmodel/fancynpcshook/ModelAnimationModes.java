package com.fancyinnovations.fancynpcsmodel.fancynpcshook;

import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;

public final class ModelAnimationModes {
    public static final AnimationModifier LOOP = AnimationModifier.builder()
            .type(AnimationIterator.Type.LOOP)
            .build();

    private ModelAnimationModes() {}
}
