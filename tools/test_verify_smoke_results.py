import contextlib
import importlib.util
import io
from pathlib import Path
import re
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET

SCRIPT = Path(__file__).resolve().parents[1] / ".github/scripts/verify-smoke-results.py"
SPEC = importlib.util.spec_from_file_location("verify_smoke_results", SCRIPT)
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)

VISUAL_HELPER_CLASSES = ("MicaPreparerIntegrationTest", "DrawableSceneInstrumentedTest", "ViewportContentSourceTest", "SurfaceLegibilityViewportTest",
                         "ViewportSoftwareInvalidationTest", "AcrylicBackdropColorTest", "SurfaceSourceFrameClipTest",
                         "PrismIntegrationTest", "ExpandedAccordionHeaderTest", "NestedAccordionDragTest")


class SmokeResultsTest(unittest.TestCase):
    def check(self, api=34, omit=None, problem=None, suite_errors="0", empty=False, malformed=False, stale=False,
              mutate=None, verbose=True, problem_key=None):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            root = ET.Element("testsuite", errors=suite_errors)
            keys = {(owner, name) for owner, names in VERIFIER.REQUIRED.items() for name in names}
            keys.add(VERIFIER.SCENE_HARDWARE_TEST if api >= 29 else VERIFIER.SCENE_PRE29_TEST)
            if api >= 31:
                keys.add(VERIFIER.FRAME_CLIP_GPU_TEST)
                keys.add(VERIFIER.ACRYLIC_GPU_TEST)
                keys.add(VERIFIER.GPU_TEST)
                keys.add(VERIFIER.LINEAR_GPU_TEST)
                keys.add(VERIFIER.PRISM_GPU_TEST)
                keys.update(VERIFIER.PANEL_STRETCH_TESTS)
                keys.update(VERIFIER.LIQUID_IDLE_TESTS)
            if api >= 33:
                keys.update(VERIFIER.ENHANCED_GPU_TESTS)
                keys.add(VERIFIER.P2_GPU_TEST)
            for owner, name in sorted(keys if not empty else set()):
                if (owner, name) == omit:
                    continue
                case = ET.SubElement(root, "testcase", classname=owner, name=name)
                if (owner, name) == (problem_key or VERIFIER.GPU_TEST) and problem:
                    ET.SubElement(case, problem)
            path = directory / "TEST-result.xml"
            if mutate:
                replacement = mutate(root)
                if replacement is not None:
                    root = replacement
            if malformed:
                path.write_text("<unfinished", encoding="utf-8")
            else:
                ET.ElementTree(root).write(path)
            with contextlib.redirect_stdout(io.StringIO()):
                return VERIFIER.verify(directory, api, time.time() + 60 if stale else 0, verbose=verbose)

    def test_complete_matrix_result_is_accepted(self):
        self.assertTrue(self.check())

    def test_expanded_headers_are_required_and_liquid_follows_the_api33_boundary(self):
        owner = VERIFIER.PREFIX + "ExpandedAccordionHeaderTest"
        liquid = (owner, "liquidExpandedHeadersKeepFourDirectionsAndHiddenPixels")
        for api in (27, 31, 33, 34):
            for name in VERIFIER.REQUIRED[owner]:
                with self.subTest(api=api, name=name):
                    self.assertFalse(self.check(api=api, omit=(owner, name)))
            with self.subTest(api=api, liquid=True):
                self.assertEqual(api >= 33, liquid in VERIFIER.required_tests(api))
                self.assertEqual(api < 33, self.check(api=api, omit=liquid))
                if api >= 33:
                    for problem in ("failure", "error", "skipped"):
                        self.assertFalse(self.check(api=api, problem=problem, problem_key=liquid))

    def test_min_sdk_does_not_require_gpu_test(self):
        self.assertTrue(self.check(api=27))

    def test_visual_helper_required_names_cover_the_actual_device_classes(self):
        source = SCRIPT.parents[2] / "sample/src/androidTest/java/com/lumen/coacervation/sample"
        for owner in VISUAL_HELPER_CLASSES:
            actual = set(re.findall(r"@Test\s+fun\s+(\w+)\s*\(", (source / f"{owner}.kt").read_text(encoding="utf-8")))
            registered = set(VERIFIER.REQUIRED[VERIFIER.PREFIX + owner])
            if owner == "DrawableSceneInstrumentedTest":
                registered.update((VERIFIER.SCENE_HARDWARE_TEST[1], VERIFIER.SCENE_PRE29_TEST[1]))
            elif owner == "AcrylicBackdropColorTest":
                registered.add(VERIFIER.ACRYLIC_GPU_TEST[1])
            elif owner == "SurfaceSourceFrameClipTest":
                registered.add(VERIFIER.FRAME_CLIP_GPU_TEST[1])
            elif owner == "PrismIntegrationTest":
                registered.add(VERIFIER.PRISM_GPU_TEST[1])
            elif owner == "ExpandedAccordionHeaderTest":
                registered.add("liquidExpandedHeadersKeepFourDirectionsAndHiddenPixels")
            with self.subTest(owner=owner):
                self.assertTrue(actual)
                self.assertEqual(actual, registered)

    def test_scene_backend_requirement_follows_the_api29_boundary(self):
        for api in (27, 28, 29, 31, 33, 34, 37):
            selected = VERIFIER.SCENE_HARDWARE_TEST if api >= 29 else VERIFIER.SCENE_PRE29_TEST
            other = VERIFIER.SCENE_PRE29_TEST if api >= 29 else VERIFIER.SCENE_HARDWARE_TEST
            with self.subTest(api=api):
                self.assertIn(selected, VERIFIER.required_tests(api))
                self.assertNotIn(other, VERIFIER.required_tests(api))
                self.assertFalse(self.check(api=api, omit=selected))
                self.assertTrue(self.check(api=api, omit=other))

    def test_each_visual_helper_regression_is_required_on_every_smoke_api(self):
        for api in (27, 31, 33, 34):
            for owner in VISUAL_HELPER_CLASSES:
                for name in VERIFIER.REQUIRED[VERIFIER.PREFIX + owner]:
                    with self.subTest(api=api, owner=owner, method=name):
                        self.assertFalse(self.check(api=api, omit=(VERIFIER.PREFIX + owner, name)))

    def test_failed_or_skipped_visual_helper_regression_is_rejected(self):
        for api in (27, 31, 33, 34):
            keys = {VERIFIER.SCENE_HARDWARE_TEST if api >= 29 else VERIFIER.SCENE_PRE29_TEST}
            if api >= 31:
                keys.add(VERIFIER.FRAME_CLIP_GPU_TEST)
                keys.add(VERIFIER.ACRYLIC_GPU_TEST)
            for owner in VISUAL_HELPER_CLASSES:
                keys.update((VERIFIER.PREFIX + owner, name) for name in VERIFIER.REQUIRED[VERIFIER.PREFIX + owner])
            for key in keys:
                for problem in ("failure", "error", "skipped"):
                    with self.subTest(api=api, method=key[1], problem=problem):
                        self.assertFalse(self.check(api=api, problem=problem, problem_key=key))

    def test_acrylic_gpu_requirement_starts_at_api31(self):
        for api in (27, 29, 30, 31, 33, 34, 37):
            with self.subTest(api=api):
                self.assertEqual(api >= 31, VERIFIER.ACRYLIC_GPU_TEST in VERIFIER.required_tests(api))
                self.assertEqual(api < 31, self.check(api=api, omit=VERIFIER.ACRYLIC_GPU_TEST))

    def test_frame_clip_gpu_requirement_starts_at_api31(self):
        for api in (27, 29, 30, 31, 33, 34, 37):
            with self.subTest(api=api):
                self.assertEqual(api >= 31, VERIFIER.FRAME_CLIP_GPU_TEST in VERIFIER.required_tests(api))
                self.assertEqual(api < 31, self.check(api=api, omit=VERIFIER.FRAME_CLIP_GPU_TEST))

    def test_native_compose_and_canvas_targets_are_required_on_min_sdk(self):
        for owner in ("NativeComposeIntegrationTest", "NativeSurfaceTargetTest"):
            for name in VERIFIER.REQUIRED[VERIFIER.PREFIX + owner]:
                with self.subTest(owner=owner, method=name):
                    self.assertFalse(self.check(api=27, omit=(VERIFIER.PREFIX + owner, name)))

    def test_zero_tests_is_rejected(self):
        self.assertFalse(self.check(empty=True))

    def test_each_mica_regression_is_required_on_every_smoke_api(self):
        owner = VERIFIER.PREFIX + "MicaIntegrationTest"
        for api in (27, 31, 33, 34):
            for name in VERIFIER.REQUIRED[owner]:
                with self.subTest(api=api, method=name):
                    self.assertFalse(self.check(api=api, omit=(owner, name)))

    def test_missing_gpu_assertion_is_rejected(self):
        self.assertFalse(self.check(omit=VERIFIER.GPU_TEST))
        self.assertFalse(self.check(omit=VERIFIER.LINEAR_GPU_TEST))

    def test_each_embedded_viewport_regression_is_required(self):
        owner = VERIFIER.PREFIX + "PanelEmbeddedViewportTest"
        for name in VERIFIER.REQUIRED[owner]:
            with self.subTest(method=name):
                self.assertFalse(self.check(api=27, omit=(owner, name)))

    def test_each_native_stretch_regression_is_required_on_supported_apis(self):
        for key in VERIFIER.PANEL_STRETCH_TESTS:
            with self.subTest(method=key[1]):
                self.assertFalse(self.check(api=31, omit=key))
        self.assertTrue(self.check(api=27))
    def test_missing_new_pixel_test_is_rejected(self):
        self.assertFalse(self.check(omit=next(iter(VERIFIER.ENHANCED_GPU_TESTS))))
    def test_missing_p2_native_format_or_pixels_is_rejected(self):
        self.assertFalse(self.check(omit=VERIFIER.P2_GPU_TEST))
        self.assertFalse(self.check(omit=(VERIFIER.PREFIX+"P2IntegrationTest","riveLoadsWithoutImplicitPlaybackAndCanClose")))
    def test_api31_does_not_require_agsl_tests(self):
        self.assertTrue(self.check(api=31))

    def test_missing_held_row_or_original_panel_regression_is_rejected(self):
        regressions = {
            "HeldRowClipBoundsTest": {
                "matchParentRowStaysInsideRetainedViewportWhileDragging",
                "paddedViewportIncludesScrollOffsetInItsBounds",
                "partiallyVisibleRowKeepsBaselineCropWithoutFurtherOverflow",
                "roundedViewportPreservesItsRealCornerDuringDiagonalDrag",
            },
            "PanelClipContainmentTest": {
                "restingPanelMasksOverflowAndTracksContentRelayout",
                "heldDragPreservesViewportAndRestoresOnlyInnerClipReliefs",
            },
            "HeldRowOverflowPortalTest": {
                "hardwareBufferContainsOnlyTheHeldControlOutsideViewport",
                "anAlreadyUnclippedViewportDoesNotDrawADuplicateSource",
                "horizontalOverflowCarriesTheSourceOverlayWithoutDuplicatingInterior",
                "realSurfaceAndTouchHighlightKeepTheirOriginalPositionThroughPortal",
                "partiallyVisibleRowAndOtherContentKeepVerticalViewportClipping",
                "cancelDisposeAndClearRemoveOnlyTheHeldOverflow",
                "releaseAndRegrabDoNotDuplicateThePortal",
            },
        }
        for owner, methods in regressions.items():
            for name in methods:
                with self.subTest(owner=owner, method=name):
                    self.assertFalse(self.check(api=27, omit=(VERIFIER.PREFIX + owner, name)))

    def test_failed_or_skipped_required_test_is_rejected(self):
        for problem in ("failure", "error", "skipped"):
            with self.subTest(problem=problem):
                self.assertFalse(self.check(problem=problem))

    def test_runner_error_without_failed_case_is_rejected(self):
        self.assertFalse(self.check(suite_errors="1"))

    def test_duplicate_case_cannot_supply_another_invocations_success(self):
        def duplicate(root):
            root.append(root[0])
        self.assertFalse(self.check(mutate=duplicate))

    def test_declared_but_missing_non_required_case_is_rejected(self):
        def incomplete(root):
            root.set("tests", str(len(root) + 1))
        self.assertFalse(self.check(mutate=incomplete))

    def test_invalid_or_negative_suite_counts_fail_closed(self):
        for count in ("unknown", "-1", "1.5", ""):
            def invalid(root):
                root.set("errors", count)
            with self.subTest(count=count):
                self.assertFalse(self.check(mutate=invalid))

    def test_aggregate_counts_and_errors_are_checked(self):
        def aggregate(root):
            parent = ET.Element("testsuites", tests=str(len(root) + 1))
            parent.append(root)
            return parent
        self.assertFalse(self.check(mutate=aggregate))

    def test_complete_nested_aggregate_is_accepted(self):
        def aggregate(root):
            parent = ET.Element("testsuites", tests=str(len(root)), errors="0", failures="0")
            parent.append(root)
            return parent
        self.assertTrue(self.check(mutate=aggregate))

    def test_summary_mode_preserves_the_same_acceptance_rules(self):
        self.assertTrue(self.check(verbose=False))
        self.assertFalse(self.check(verbose=False, problem="failure"))
        self.assertFalse(self.check(verbose=False, omit=VERIFIER.GPU_TEST))

    def test_unreadable_result_is_rejected(self):
        self.assertFalse(self.check(malformed=True))

    def test_report_from_a_previous_invocation_is_rejected(self):
        self.assertFalse(self.check(stale=True))

    def test_absent_reports_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            self.assertFalse(VERIFIER.verify(Path(temporary), 34))

    def test_liquid_idle_cases_are_required_from_api31_and_missing_cases_fail(self):
        for key in VERIFIER.LIQUID_IDLE_TESTS:
            self.assertNotIn(key, VERIFIER.required_tests(27))
            self.assertIn(key, VERIFIER.required_tests(31))
            self.assertFalse(self.check(api=31, omit=key))

    def test_slider_cases_are_required_on_every_api_and_missing_cases_fail(self):
        owner = VERIFIER.PREFIX + "LumenSliderIntegrationTest"
        for api in (27, 31, 33, 34):
            for name in VERIFIER.REQUIRED[owner]:
                self.assertIn((owner, name), VERIFIER.required_tests(api))
        self.assertFalse(self.check(api=27, omit=(owner, "capsuleFillCoversExactlyTheValue")))

    def test_material_opacity_cases_are_required_on_every_api_and_missing_cases_fail(self):
        owner = VERIFIER.PREFIX + "MaterialOpacityRegressionTest"
        for api in (27, 31, 33, 34):
            for name in VERIFIER.REQUIRED[owner]:
                self.assertIn((owner, name), VERIFIER.required_tests(api))
        self.assertFalse(self.check(api=31, omit=(owner, "aSettledOpacityLeavesTheWindowIdle")))


if __name__ == "__main__":
    unittest.main()
