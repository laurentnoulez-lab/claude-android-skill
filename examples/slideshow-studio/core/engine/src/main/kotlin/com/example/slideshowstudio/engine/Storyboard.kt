package com.example.slideshowstudio.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/** One photo inside a scene: which photo, where, and how it moves. */
data class SlotPlan(
    val photoIndex: Int,
    val rect: NormRect,
    val motion: MotionSpec,
)

/** A composition shown for one scene duration, plus the transition that brings it in. */
data class Scene(
    val index: Int,
    val layoutId: String,
    val slots: List<SlotPlan>,
    val transitionIn: TransitionSpec?,
) {
    val photoCount: Int get() = slots.size
}

/** The full plan of the video. Deterministic for a given photo list + settings (including the seed). */
data class Storyboard(
    val settings: SlideshowSettings,
    val scenes: List<Scene>,
) {
    val sceneDurationSeconds: Float get() = settings.sceneDurationSeconds
    val transitionDurationSeconds: Float get() = settings.effectiveTransitionSeconds
    val totalDurationSeconds: Float get() = scenes.size * sceneDurationSeconds
    val frameCount: Int get() = (totalDurationSeconds * settings.fps).roundToInt()

    val isEmpty: Boolean get() = scenes.isEmpty()
}

/**
 * Turns a list of photos into a storyboard.
 *
 * Rules enforced here:
 *  - a photo is never used twice inside the same scene;
 *  - every photo is used at least once;
 *  - two consecutive scenes do not show the same number of photos when the mode allows a choice;
 *  - the same composition is not reused twice in a row;
 *  - inside a scene, no two photos share the same movement, and movements differ from the previous scene;
 *  - two consecutive transitions never belong to the same family.
 */
object StoryboardBuilder {

    fun build(photos: List<PhotoRef>, rawSettings: SlideshowSettings): Storyboard {
        val settings = rawSettings.sanitized()
        if (photos.isEmpty()) return Storyboard(settings, emptyList())

        val random = Random(settings.seed)
        val deck = photos.indices.shuffled(random)
        val templatesByCount = (1..4).associateWith { LayoutCatalog.templatesFor(it, settings.canvasAspect) }

        val scenes = mutableListOf<Scene>()
        var cursor = 0
        var previousCount = 0
        var previousTemplateId: String? = null
        val lastTemplateForCount = mutableMapOf<Int, String>()
        var previousTransition: TransitionKind? = null
        var previousMotions: Set<MotionKind> = emptySet()

        while (cursor < deck.size) {
            val remaining = deck.size - cursor
            val maxCount = minOf(settings.mode.maxImages, photos.size, remaining)
            val count = chooseCount(maxCount, previousCount, random)
            val picks = deck.subList(cursor, cursor + count).toList()
            cursor += count

            val templates = templatesByCount.getValue(count)
            val template = chooseTemplate(templates, previousTemplateId, lastTemplateForCount[count], random)
            val ordered = assignPhotosToSlots(picks, template.slots, photos, settings.canvasAspect)

            val usedMotions = mutableSetOf<MotionKind>()
            val slots = ordered.mapIndexed { slotIndex, photoIndex ->
                val kind = MotionFactory.pickKind(random, avoid = usedMotions + previousMotions)
                usedMotions += kind
                SlotPlan(
                    photoIndex = photoIndex,
                    rect = template.slots[slotIndex],
                    motion = MotionFactory.create(kind, random),
                )
            }

            val transition = if (scenes.isEmpty()) {
                null
            } else {
                val kind = TransitionFactory.pickKind(random, previousTransition)
                previousTransition = kind
                TransitionFactory.create(kind, random)
            }

            scenes += Scene(
                index = scenes.size,
                layoutId = template.id,
                slots = slots,
                transitionIn = transition,
            )

            previousCount = count
            previousTemplateId = template.id
            lastTemplateForCount[count] = template.id
            previousMotions = usedMotions
        }

        return Storyboard(settings, scenes)
    }

    /** Uniform pick inside the allowed range, excluding the previous count when there is a choice. */
    private fun chooseCount(maxCount: Int, previousCount: Int, random: Random): Int {
        if (maxCount <= 1) return 1
        val candidates = (1..maxCount).filter { it != previousCount }
        val pool = candidates.ifEmpty { (1..maxCount).toList() }
        return pool[random.nextInt(pool.size)]
    }

    private fun chooseTemplate(
        templates: List<LayoutTemplate>,
        previousTemplateId: String?,
        lastForThisCount: String?,
        random: Random,
    ): LayoutTemplate {
        val avoided = setOfNotNull(previousTemplateId, lastForThisCount)
        val candidates = templates.filterNot { it.id in avoided }
        val pool = candidates.ifEmpty { templates.filterNot { it.id == previousTemplateId }.ifEmpty { templates } }
        return pool[random.nextInt(pool.size)]
    }

    /**
     * Matches photos with slots so that portrait photos land in tall slots and landscape photos in
     * wide ones. With at most four photos per scene, testing every permutation is cheap and gives
     * the assignment that crops the least.
     */
    internal fun assignPhotosToSlots(
        picks: List<Int>,
        slots: List<NormRect>,
        photos: List<PhotoRef>,
        canvasAspect: Float,
    ): List<Int> {
        if (picks.size <= 1) return picks
        val slotAspects = slots.map { it.pixelAspect(canvasAspect) }
        var best = picks
        var bestCost = Float.MAX_VALUE
        permutations(picks) { candidate ->
            var cost = 0f
            for (i in candidate.indices) {
                val photoAspect = photos[candidate[i]].aspect
                cost += abs(ln(photoAspect / slotAspects[i]))
            }
            if (cost < bestCost) {
                bestCost = cost
                best = candidate.toList()
            }
        }
        return best
    }

    private fun permutations(items: List<Int>, onPermutation: (List<Int>) -> Unit) {
        val working = items.toMutableList()
        fun recurse(index: Int) {
            if (index == working.size) {
                onPermutation(working)
                return
            }
            for (i in index until working.size) {
                working[index] = working[i].also { working[i] = working[index] }
                recurse(index + 1)
                working[index] = working[i].also { working[i] = working[index] }
            }
        }
        recurse(0)
    }
}
