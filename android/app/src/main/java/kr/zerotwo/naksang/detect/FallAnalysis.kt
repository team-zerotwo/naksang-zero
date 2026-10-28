package kr.zerotwo.naksang.detect

/**
 * The production suspicion path: rule, then pose model, then lying-still.
 * A suspicion is the OR of the three booleans. The test screen reads
 * [FallFrame.debug] and does not start the countdown.
 *
 * Call [onFrame] once per analysis frame. Do not reimplement the combination
 * beside this class.
 */
class FallAnalysis(config: FallConfig = FallConfig()) {
    private val detector = FallDetector(config)
    private val scorer = PoseFallScorer(enabled = config.modelEnabled)
    private val lying = LyingStillDetector(config)
    private val fast = FastFlattenDetector()

    fun updateConfig(config: FallConfig) {
        detector.updateConfig(config)
        scorer.update(config.modelEnabled)
        lying.updateConfig(config)
    }

    fun reset() {
        detector.reset()
        scorer.reset()
        lying.reset()
        fast.reset()
    }

    fun onFrame(timestampMs: Long, joints: Array<FloatArray>?): FallFrame {
        // Same order and same null skip as the previous production calls.
        // A missing pose does not enter the rule detector, so a gap is still
        // measured on the next visible frame.
        val ruleHit = if (joints != null) {
            detector.onJointArray(timestampMs, joints)
        } else {
            detector.noteAbsent()
            false
        }
        val modelHit = scorer.onFrame(timestampMs, joints)
        val lyingHit = lying.onJointArray(timestampMs, joints)
        val fastHit = fast.onJointArray(timestampMs, joints)
        val rule = detector.ruleDebug()
        val model = scorer.modelDebug()
        val lie = lying.lyingDebug()
        val cfg = detector.config
        val wouldFire = linkedSetOf<DetectSignal>()
        if (rule.ruleFired) wouldFire.add(DetectSignal.RULE)
        if (rule.viewFired) wouldFire.add(DetectSignal.VIEW_DIRECTION)
        if (model.fired) wouldFire.add(DetectSignal.MODEL)
        if (lie.fired) wouldFire.add(DetectSignal.LYING_STILL)
        if (fastHit) wouldFire.add(DetectSignal.FAST_FLATTEN)
        val posture = postureLabel(
            measured = rule.measured,
            torsoAngleDeg = rule.angleDeg,
            hipY = rule.hipY,
            kneeY = rule.kneeY,
            lieAngleDeg = cfg.maxLieAngleDeg,
            highAngleDeg = cfg.lyingHighAngleDeg,
        )
        val snapshot = FallDebugSnapshot(
            personDetected = joints != null,
            posture = posture,
            torsoAngleDeg = rule.angleDeg,
            poseConfidence = meanLandmarkVisibility(joints),
            lyingElapsedMs = lie.elapsedMs,
            lyingThresholdMs = lie.thresholdMs,
            lyingUnwitnessedMs = cfg.lyingStillUnwitnessedMs,
            lyingCounting = lie.counting,
            lyingWitnessedFast = lie.witnessedFast,
            lyingEnabled = cfg.lyingStillEnabled,
            modelScore = model.score,
            modelGbdtScore = model.gbdtScore,
            modelGbdtThreshold = PoseFallWeights.GBDT_THRESHOLD,
            modelThreshold = model.threshold,
            modelStreak = model.streak,
            modelHoldFrames = model.holdFrames,
            modelEnabled = model.enabled,
            ruleLatched = rule.ruleLatched,
            viewLatched = rule.viewLatched,
            ruleStillElapsedMs = rule.stillElapsedMs,
            ruleStillNeedMs = rule.stillNeedMs,
            wouldFire = wouldFire,
        )
        return FallFrame(
            suspected = ruleHit || modelHit || lyingHit || fastHit,
            debug = snapshot,
        )
    }
}
