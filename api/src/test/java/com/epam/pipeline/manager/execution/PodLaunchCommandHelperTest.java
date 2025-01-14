package com.epam.pipeline.manager.execution;

import com.epam.pipeline.entity.execution.OSSpecificLaunchCommandTemplate;
import com.epam.pipeline.entity.scan.ToolOSVersion;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class PodLaunchCommandHelperTest {

    public static final String COMMAND_FOR_ALL_IMAGES = "command_for_all_images";
    public static final String COMMAND_FOR_CENTOS_7_IMAGE = "command_for_centos_7_image";
    public static final String COMMAND_FOR_ALL_OTHER_CENTOS_IMAGES = "command_for_all_other_centos_images";
    public static final String COMMAND_FOR_DOCKER = "docker";

    public static final String VALUE = "value";
    public static final String EVALUATED = "evaluated";
    public static final String LAUNCH_COMMAND_TEMPLATE = "command template with: $" + VALUE;
    public static final String EVALUATED_LAUNCH_COMMAND_TEMPLATE = "command template with: " + EVALUATED;

    public static final String LAUNCH_COMMAND_TEMPLATE_WITH_ADDITIONAL_VARS = "command template with: $"
            + VALUE + " and additional $ENV_VAR";
    public static final String EVALUATED_LAUNCH_COMMAND_TEMPLATE_WITH_ADDITIONAL_VARS = "command template with: "
            + EVALUATED + " and additional $ENV_VAR";
    public static final String CENTOS_OS = "centos";
    public static final String UBUNTU_OS = "ubuntu";

    static final String CENTOS_SHORT_IMAGE_WITHOUT_TAG = "library/centos";
    static final String ROCKY_SHORT_IMAGE_WITHOUT_TAG = "library/rocky";
    static final String CENTOS_SHORT_IMAGE_WITH_TAG = "library/centos:latest";
    static final String CENTOS_FULL_IMAGE_WITHOUT_TAG = "registry:443/library/centos";
    static final String CENTOS_FULL_IMAGE_WITH_TAG = "registry:443/library/centos:latest";
    static final String CENTOS_FULL_IMAGE_WITH_TAG2 = "registry:443/library/centos:7";

    private static final List<OSSpecificLaunchCommandTemplate> COMMAND_TEMPLATES = Arrays.asList(
        OSSpecificLaunchCommandTemplate.builder().os("*").command(COMMAND_FOR_ALL_IMAGES).build(),
        OSSpecificLaunchCommandTemplate.builder().os("centos:7").command(COMMAND_FOR_CENTOS_7_IMAGE).build(),
        OSSpecificLaunchCommandTemplate.builder().os(CENTOS_OS)
                .command(COMMAND_FOR_ALL_OTHER_CENTOS_IMAGES).build(),
        OSSpecificLaunchCommandTemplate.builder().command(COMMAND_FOR_DOCKER)
                .docker(ROCKY_SHORT_IMAGE_WITHOUT_TAG).build()
    );

    private static final List<OSSpecificLaunchCommandTemplate> COMMAND_TEMPLATES_WRONG_ORDER = Arrays.asList(
        OSSpecificLaunchCommandTemplate.builder().os("*").command(COMMAND_FOR_ALL_IMAGES).build(),
        OSSpecificLaunchCommandTemplate.builder().os(CENTOS_OS)
                .command(COMMAND_FOR_ALL_OTHER_CENTOS_IMAGES).build(),
        OSSpecificLaunchCommandTemplate.builder().os("centos:7").command(COMMAND_FOR_CENTOS_7_IMAGE).build()
    );

    @Test
    public void shouldPickDefaultLaunchCommandTemplateTest() {
        final String ubuntuLaunchCommand = PodLaunchCommandHelper.pickLaunchCommandTemplate(
                        COMMAND_TEMPLATES,
                        ToolOSVersion.builder().distribution(UBUNTU_OS).version("18.04").build(),
                CENTOS_FULL_IMAGE_WITH_TAG).getCommand();
        Assert.assertEquals(COMMAND_FOR_ALL_IMAGES, ubuntuLaunchCommand);
    }

    @Test
    public void shouldPickTheMostAccurateLaunchCommandTemplateTest() {
        final String centos7LaunchCommand = PodLaunchCommandHelper.pickLaunchCommandTemplate(
                COMMAND_TEMPLATES,
                ToolOSVersion.builder().distribution(CENTOS_OS).version("7").build(),
                CENTOS_FULL_IMAGE_WITH_TAG).getCommand();
        Assert.assertEquals(COMMAND_FOR_CENTOS_7_IMAGE, centos7LaunchCommand);
    }

    @Test
    public void shouldMatchTemplateWithDocker() {
        final String centos7LaunchCommand = PodLaunchCommandHelper.pickLaunchCommandTemplate(
                COMMAND_TEMPLATES,
                ToolOSVersion.builder().distribution(CENTOS_OS).version("7").build(),
                ROCKY_SHORT_IMAGE_WITHOUT_TAG).getCommand();
        Assert.assertEquals(COMMAND_FOR_DOCKER, centos7LaunchCommand);
    }

    @Test
    public void shouldPickCentosCommonLaunchCommandTemplateTest() {
        final String centos8LaunchCommand = PodLaunchCommandHelper.pickLaunchCommandTemplate(
                COMMAND_TEMPLATES,
                ToolOSVersion.builder().distribution(CENTOS_OS).version("8").build(),
                CENTOS_FULL_IMAGE_WITH_TAG).getCommand();
        Assert.assertEquals(COMMAND_FOR_ALL_OTHER_CENTOS_IMAGES, centos8LaunchCommand);
    }

    @Test
    public void willPickTheWrongLaunchCommandTemplateBecauseOfWrongOrderOfCommandsInPreferenceTest() {
        final String centos7LaunchCommand = PodLaunchCommandHelper.pickLaunchCommandTemplate(
                COMMAND_TEMPLATES_WRONG_ORDER,
                ToolOSVersion.builder().distribution(CENTOS_OS).version("7").build(),
                CENTOS_FULL_IMAGE_WITH_TAG).getCommand();
        Assert.assertNotEquals(COMMAND_FOR_CENTOS_7_IMAGE, centos7LaunchCommand);
        Assert.assertEquals(COMMAND_FOR_ALL_OTHER_CENTOS_IMAGES, centos7LaunchCommand);
    }

    @Test
    public void shouldEvaluateLaunchCommandTemplateTest() {
        final String evaluatedCommand = PodLaunchCommandHelper.evaluateLaunchCommandTemplate(
                LAUNCH_COMMAND_TEMPLATE, Collections.singletonMap(VALUE, EVALUATED));
        Assert.assertEquals(EVALUATED_LAUNCH_COMMAND_TEMPLATE, evaluatedCommand);
    }

    @Test
    public void shouldEvaluateOnlySpecificVarsInLaunchCommandTemplateTest() {
        final String evaluatedCommand = PodLaunchCommandHelper.evaluateLaunchCommandTemplate(
                LAUNCH_COMMAND_TEMPLATE_WITH_ADDITIONAL_VARS, Collections.singletonMap(VALUE, EVALUATED));
        Assert.assertEquals(EVALUATED_LAUNCH_COMMAND_TEMPLATE_WITH_ADDITIONAL_VARS, evaluatedCommand);
    }

    @Test
    public void shouldMatchImageWithoutTag() {
        Assert.assertTrue(PodLaunchCommandHelper.matchImage(CENTOS_SHORT_IMAGE_WITHOUT_TAG,
                CENTOS_FULL_IMAGE_WITHOUT_TAG));
    }

    @Test
    public void shouldMatchImageWithoutTagAndRepo() {
        Assert.assertTrue(PodLaunchCommandHelper.matchImage(CENTOS_SHORT_IMAGE_WITHOUT_TAG,
                CENTOS_SHORT_IMAGE_WITHOUT_TAG));
    }

    @Test
    public void shouldMathImageWithTag() {
        Assert.assertTrue(PodLaunchCommandHelper.matchImage(CENTOS_SHORT_IMAGE_WITH_TAG,
                CENTOS_FULL_IMAGE_WITH_TAG));
    }

    @Test
    public void shouldNotMatchImageWithoutTag() {
        Assert.assertFalse(PodLaunchCommandHelper.matchImage(CENTOS_SHORT_IMAGE_WITH_TAG,
                CENTOS_FULL_IMAGE_WITHOUT_TAG));
    }

    @Test
    public void shouldNotMatchImageWithWrongTag() {
        Assert.assertFalse(PodLaunchCommandHelper.matchImage(CENTOS_SHORT_IMAGE_WITH_TAG,
                CENTOS_FULL_IMAGE_WITH_TAG2));
    }
}