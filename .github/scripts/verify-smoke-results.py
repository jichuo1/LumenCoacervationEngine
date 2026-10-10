#!/usr/bin/env python3
"""Do not accept a successful Gradle exit without successful required device tests."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

PREFIX = "com.lumen.coacervation.sample."
REQUIRED = {
    PREFIX + "P2IntegrationTest": {
        "sensorIsOptInAndStopsHiddenPausedOrClosed",
        "fixedParticlesExpireAndDecorationPreservesBusinessView",
        "parameterEnvelopeRoundTripsAndRejectsWrongKnownTypes",
        "preservedParticlesFreezeDuringPauseAndResumeWithTheirRemainingLifetime",
        "staleDecodeClosesItsHandleAndRendererFailurePreservesForeignChildren",
        "oversizedStreamNeverInvokesTheParser",
        "shrinkingTheByteBudgetDuringLoadRejectsTheCompletedOldPolicy",
        "displayPlayerDoesNotReceiveBusinessTouchesOrWakeFromPause",
        "lottieLoadsWithoutImplicitPlaybackAndCanClose",
        "pagLoadsWithoutImplicitPlaybackAndCanClose",
        "riveLoadsWithoutImplicitPlaybackAndCanClose",
        "riveStateInputsValidateNamesAndTypesAndUnknownDurationHasATimeLimit",
    },
    PREFIX + "DemoSmokeTest": {
        "everyPageRendersAndScrollsInBothMaterials",
        "panelsBubblesAndFullscreenMorphOpenAndClose",
        "motionPageAccordionScrubAndRevealRun",
        "longPressDragWorksAtEveryTuningExtreme",
        "everySettingRecreatesCleanly",
        "customBackgroundImportsAndRestores",
        "rotationKeepsPageAndAdaptsLayout",
    },
    PREFIX + "SourceEffectCompatibilityInstrumentedTest": {
        "parentBoundsPreservesOverlapAndChangingPolicyDoesNotRetargetActiveDrag",
        "capsuleRuleCanBeChangedForTheNextModalWithoutInterruptingTheVisibleOne",
    },
    PREFIX + "LocalSurfaceIntegrationTest": {
        "bindingPreservesTheWindowHierarchyPaddingAndUnrelatedBackgrounds",
        "sourceCannotCaptureItsOwnInjectedSurface",
        "forcedStaticBackendDoesNotRequireAContentSource",
        "softwareCaptureProducesAFrameAndPaletteChangesDoNotRecreateTheActivity",
        "mixedSoftwareIntervalsRemainIndependentAndTheFinalSlowFrameIsSampled",
        "foreignWindowSourceIsRejectedAndLateCallsAfterCloseAreHarmless",
    },
    PREFIX + "EnhancedSurfaceIntegrationTest": {
        "presetsRoundTripAllGroupsAndRejectInvalidKnownValues",
        "opaqueAccessibleIntentNeedsNoCaptureAndKeepsTheOriginalClick",
        "prohibitedDependencyStopsBeforeFirstCaptureAndRecoversAfterAvailabilityChanges",
        "fusedSelectorKeepsBusinessSelectionAndRestoresOnlyOwnedResources",
    },
    PREFIX + "SurfaceBenchmarkTest": {"compareFixedSceneWithLegacyAndEnhancedPlansAndPreserveRawSamples"},
    PREFIX + "PanelClipContainmentTest": {
        "restingPanelMasksOverflowAndTracksContentRelayout",
        "heldDragPreservesViewportAndRestoresOnlyInnerClipReliefs",
    },
    PREFIX + "HeldRowClipBoundsTest": {
        "matchParentRowStaysInsideRetainedViewportWhileDragging",
        "paddedViewportIncludesScrollOffsetInItsBounds",
        "partiallyVisibleRowKeepsBaselineCropWithoutFurtherOverflow",
        "roundedViewportPreservesItsRealCornerDuringDiagonalDrag",
    },
}
GPU_TEST = (PREFIX + "LocalSurfaceIntegrationTest", "gpuFadeUsesTheCurrentSourceAndDoesNotTintTheWholeWindow")
P2_GPU_TEST = (PREFIX + "P2IntegrationTest", "proceduralGeneratorsActuallyDrawAndDoNotRecompileForParticleFrames")
ENHANCED_GPU_TESTS = {
    (PREFIX + "EnhancedSurfaceIntegrationTest", "fusionPressAndLightCompileAndContinuousFramesDoNotBuildRenderEffects"),
    (PREFIX + "EnhancedSurfaceIntegrationTest", "progressiveBlurProducesAnActualWindowImageAndStaysWithinBudget"),
    *((PREFIX + "SurfacePixelContractTest", name) for name in (
        "smoothFusionFillsTheBridgeButTheHardUnionDoesNot",
        "asymmetricLargeCornerMatchesTheCanvasContourAwayFromAntialiasing",
        "progressiveBlurReducesStripeContrastAndCanReverseDirection",
        "localPressChangesNearbyPixelsAndLeavesDistantPixelsStable",
        "sdrTransparentInputAndTintFollowPremultipliedComposition",
        "paddedExperimentCanReadTheOutsideMarkerAndReportsActualFiveBounds",
        "rotatingTheSurfaceTransformsItsLightingNormal",
    )),
}


def verify(directory: Path, api: int, since: float = 0) -> bool:
    required = {(owner, name) for owner, methods in REQUIRED.items() for name in methods}
    if api >= 31:
        required.add(GPU_TEST)
    if api >= 33:
        required.update(ENHANCED_GPU_TESTS)
        required.add(P2_GPU_TEST)
    files = sorted(path for path in directory.rglob("*.xml") if path.stat().st_mtime >= since)
    passed = set()
    total = failed = skipped = 0
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except (ET.ParseError, OSError) as error:
            print(f"FAIL report: {path.name}: {error}")
            failed += 1
            continue
        for suite in root.iter("testsuite"):
            if int(suite.get("errors", "0")) or int(suite.get("failures", "0")):
                failed += 1
        for case in root.iter("testcase"):
            total += 1
            key = (case.get("classname", ""), case.get("name", ""))
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                failed += 1
                print(f"FAIL {key[0]}.{key[1]}")
                print((problem.text or problem.get("message") or "")[:4000])
            elif case.find("skipped") is not None:
                skipped += 1
                print(f"SKIP {key[0]}.{key[1]}")
            else:
                passed.add(key)
                print(f"PASS {key[0]}.{key[1]} ({case.get('time', '?')}s)")
    missing = required - passed
    for owner, name in sorted(missing):
        print(f"MISSING required successful test: {owner}.{name}")
    print(f"合计 {total} 条，失败或错误 {failed} 条，跳过 {skipped} 条，必测缺失 {len(missing)} 条")
    return bool(files) and total > 0 and failed == 0 and not missing


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--since", type=float, default=0)
    args = parser.parse_args()
    raise SystemExit(0 if verify(args.directory, args.api, args.since) else 1)
