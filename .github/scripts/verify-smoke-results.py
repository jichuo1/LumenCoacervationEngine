#!/usr/bin/env python3
"""Do not accept a successful Gradle exit without successful required device tests."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

PREFIX = "com.lumen.coacervation.sample."
REQUIRED = {
    PREFIX + "MicaIntegrationTest": {
        "backgroundPreparationIsBoundedBorrowedAndMainThreadGuarded",
        "fallbackColoursAreOpaqueAndHighContrastHonoursHostPalette",
        "activeBaseAndAltRenderDifferentColoursAndRepeatedDrawsDoNotCreateSnapshots",
        "layerFillsUseWinUiTokensWithoutDuplicatingTheBackdrop",
        "memoryPressureDropsSnapshotsUntilHostExplicitlyRestoresTheImage",
        "closeRestoresOnlyOwnedBackgroundAndPreservesPaddingAndLateCalls",
        "windowFocusPolicyCanBeRestoredWithoutPreparingAnotherImage",
        "gpuWindowOutputMatchesThePreparedMaterial",
    },
    PREFIX + "PanelInlineScrollTest": {
        "preparationVisitsInlineScrollsInsideInstalledAndExistingViewports",
        "concurrentScrollOwnersStayWindowIsolatedAndDetachedZeroReturnsToItsOwner",
    },
    PREFIX + "NativeSurfaceTargetTest": {
        "tintOnlyNativeUpdatesPreserveReadyStateAndHostBackground",
        "independentTargetsPreserveTheHostAndClosingOneKeepsTheOtherBound",
        "softwareSamplingUsesEachTargetsPositionAndStopsWhenStatic",
    },
    PREFIX + "NativeComposeIntegrationTest": {
        "independentBindingsShareOneComposeRootAndIdleSourceStopsRecording",
        "movingACardSamplesTheNewPositionWithoutMovingTheBackdrop",
        "changingSelectionRestoresTheOldBackgroundAndMovesItsOwnSample",
        "lazyKeyReplacementAndRemovingTheCompositionReleaseEveryBinding",
        "heldHorizontalDragMovesMaterialAndContentAndReleasesWithoutClicking",
        "anEarlySlopCrossingCannotBecomeAHoldLaterInTheSameStream",
        "aSurfaceInsideTheBackdropCannotFeedBackIntoItsOwnSource",
    },
    PREFIX + "SurfaceTintContinuityTest": {"tintUpdatesKeepReadyFramesWhileGeometryChangesStillRebuild"},
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
    PREFIX + "PanelEmbeddedViewportTest": {
        "limitedPanelScrollKeepsNaturalHeightAndWrapsOnce",
        "inlineScrollMasksAllCornersAndTracksRelayout",
        "decoratedEmbeddedPageMasksItsBackgroundAndKeepsInnerListSquare",
        "explicitChildOutlineRemainsAuthoritativeAcrossLayouts",
    },
    PREFIX + "HeldRowClipBoundsTest": {
        "matchParentRowStaysInsideRetainedViewportWhileDragging",
        "paddedViewportIncludesScrollOffsetInItsBounds",
        "partiallyVisibleRowKeepsBaselineCropWithoutFurtherOverflow",
        "roundedViewportPreservesItsRealCornerDuringDiagonalDrag",
    },
    PREFIX + "HeldRowOverflowPortalTest": {
        "hardwareFourDirectionsPreserveMaterialForegroundAndHighlightThroughNestedViewports",
        "hardwareSelectedBackdropAndReadableSourceOutlineFollowAllFourDirections",
        "baselineCropMovesWithoutRevealingHiddenPixelsAndForeignClipChangesArePreserved",
        "changingARoundOutlineInsideTheSameProviderEndsThePortalWithoutOpeningNativeClips",
        "managedNestedStretchViewportKeepsHorizontalOverflowAndVerticalContainment",
        "hardwareRoundedNestedViewportCarriesOnlyTheHeldControlIntoPadding",
        "hardwareSelectedBackdropFollowsTheHeldOptionOutsideRoundedViewport",
        "hardwareBufferContainsOnlyTheHeldControlOutsideViewport",
        "anAlreadyUnclippedViewportDoesNotDrawADuplicateSource",
        "horizontalOverflowCarriesTheSourceOverlayWithoutDuplicatingInterior",
        "realSurfaceAndTouchHighlightKeepTheirOriginalPositionThroughPortal",
        "partiallyVisibleRowAndOtherContentKeepVerticalViewportClipping",
        "cancelDisposeAndClearRemoveOnlyTheHeldOverflow",
        "releaseAndRegrabDoNotDuplicateThePortal",
    },
    PREFIX + "PanelSurfaceGeometryTest": {
        "selectedBackgroundRerecordsDuringRowTransformAndStaysIdleAfterwards",
        "bitmapSamplingTransformMatchesNativeWarpAtSurfaceEndpoints",
    },
}
GPU_TEST = (PREFIX + "LocalSurfaceIntegrationTest", "gpuFadeUsesTheCurrentSourceAndDoesNotTintTheWholeWindow")
LINEAR_GPU_TEST = (PREFIX + "LocalSurfaceIntegrationTest", "linearGpuFadeHasUniformPixelCoverage")
PANEL_STRETCH_TESTS = {
    (PREFIX + "PanelInlineScrollTest", "platformFlingReboundsInTheNearestViewportWithoutMovingTheOuterScroll"),
    (PREFIX + "PanelInlineScrollTest", "idleAndFinishingInlineViewportsPreserveAnActiveOuterRebound"),
    (PREFIX + "PanelSurfaceGeometryTest", "dialogInputReadsCurrentNativeStretchCoordinatesAndClearsAfterRelease"),
    (PREFIX + "PanelFlingHandoffTest", "fastFlingReboundsAndTouchTakesOwnership"),
    (PREFIX + "PanelStretchWindowIsolationTest", "dialogReboundDoesNotRerecordTheStationaryActivityBehindIt"),
}
P2_GPU_TEST = (PREFIX + "P2IntegrationTest", "proceduralGeneratorsActuallyDrawAndDoNotRecompileForParticleFrames")
ENHANCED_GPU_TESTS = {
    (PREFIX + "LocalSurfaceIntegrationTest", "linearEnhancedGpuFadeHasUniformPixelCoverage"),
    (PREFIX + "NativeComposeIntegrationTest", "nativeEnhancedPressChangesPixelsAndUniformUpdatesKeepTheShaderCache"),
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


def required_tests(api):
    required = {(owner, name) for owner, methods in REQUIRED.items() for name in methods}
    if api >= 31:
        required.add(GPU_TEST)
        required.add(LINEAR_GPU_TEST)
        required.update(PANEL_STRETCH_TESTS)
    if api >= 33:
        required.update(ENHANCED_GPU_TESTS)
        required.add(P2_GPU_TEST)
    return required


def verify(directory: Path, api: int, since: float = 0, verbose: bool = True) -> bool:
    required = required_tests(api)
    passed = set()
    seen = set()
    total = failed = skipped = 0
    reports = 0
    for path in sorted(directory.rglob("*.xml")):
        try:
            if path.stat().st_mtime < since:
                continue
            reports += 1
            # Process each case once and release it immediately. Long failure logs do not retain a full XML tree.
            counts = {}
            file_cases = 0
            root = None
            with path.open("rb") as source:
                for _, element in ET.iterparse(source, events=("end",)):
                    root = element
                    if element.tag == "testcase":
                        total += 1
                        file_cases += 1
                        key = (element.get("classname", ""), element.get("name", ""))
                        if not all(key) or key in seen:
                            failed += 1
                            print(f"FAIL missing or duplicate test identity: {key[0]}.{key[1]}")
                        seen.add(key)
                        problem = element.find("failure")
                        if problem is None:
                            problem = element.find("error")
                        if problem is not None:
                            failed += 1
                            print(f"FAIL {key[0]}.{key[1]}")
                            print((problem.text or problem.get("message") or "")[:4000])
                        elif element.find("skipped") is not None:
                            skipped += 1
                            print(f"SKIP {key[0]}.{key[1]}")
                        else:
                            if key in required:
                                passed.add(key)
                            if verbose:
                                print(f"PASS {key[0]}.{key[1]} ({element.get('time', '?')}s)")
                        element.clear()
                    elif element.tag in ("testsuite", "testsuites"):
                        for name in ("errors", "failures", "skipped", "tests"):
                            value = element.get(name)
                            if value is not None and (not value.isdecimal()):
                                raise ValueError(f"invalid suite {name}: {value}")
                        if int(element.get("errors", "0")) or int(element.get("failures", "0")):
                            failed += 1
                        count = len(element.findall("testcase"))
                        if element.tag == "testsuites" and count:
                            raise ValueError("testcase outside a testsuite")
                        for child in element.findall("testsuite") + element.findall("testsuites"):
                            count += counts.pop(child)
                        if element.get("tests") is not None and int(element.get("tests")) != count:
                            raise ValueError(f"incomplete suite: declared {element.get('tests')}, found {count}")
                        counts[element] = count
                        element.clear()
            if root is None or root.tag not in ("testsuite", "testsuites") or counts.get(root) != file_cases:
                raise ValueError("invalid report root or testcase outside a suite")
        except (ET.ParseError, OSError, ValueError) as error:
            print(f"FAIL report: {path.name}: {error}")
            failed += 1
    missing = required - passed
    for owner, name in sorted(missing):
        print(f"MISSING required successful test: {owner}.{name}")
    print(f"合计 {total} 条，失败或错误 {failed} 条，跳过 {skipped} 条，必测缺失 {len(missing)} 条")
    return reports > 0 and total > 0 and failed == 0 and not missing


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    parser.add_argument("--api", type=int, required=True)
    parser.add_argument("--since", type=float, default=0)
    parser.add_argument("--summary", action="store_true", help="Print failures, skips and totals; omit passing-case noise")
    args = parser.parse_args()
    raise SystemExit(0 if verify(args.directory, args.api, args.since, verbose=not args.summary) else 1)
