package io.qameta.allure.junit4;

import io.qameta.allure.Allure;
import io.qameta.allure.Lifecycle;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Owner;
import io.qameta.allure.Severity;
import io.qameta.allure.Story;
import io.qameta.allure.model.Label;
import io.qameta.allure.model.Link;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.util.ResultsUtils;
import org.junit.Ignore;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.qameta.allure.util.ResultsUtils.getHostName;
import static io.qameta.allure.util.ResultsUtils.getStackTraceAsString;
import static io.qameta.allure.util.ResultsUtils.getStatus;
import static io.qameta.allure.util.ResultsUtils.getThreadName;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Allure Junit4 listener.
 */
@RunListener.ThreadSafe
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.CouplingBetweenObjects", "checkstyle:ClassFanOutComplexity"})
public class AllureJunit4 extends RunListener {

    public static final String MD_5 = "md5";

    private final ThreadLocal<String> testCases = new InheritableThreadLocal<String>() {
        @Override
        protected String initialValue() {
            return UUID.randomUUID().toString();
        }
    };

    private final Lifecycle lifecycle;

    public AllureJunit4() {
        this(Allure.getLifecycle());
    }

    public AllureJunit4(final Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    @Override
    public void testRunStarted(final Description description) throws Exception {
        //do nothing
    }

    @Override
    public void testRunFinished(final Result result) throws Exception {
        //do nothing
    }

    @Override
    public void testStarted(final Description description) throws Exception {
        final String uuid = testCases.get();
        final TestResult result = createTestResult(uuid, description);
        getLifecycle().startTest(result);
    }

    @Override
    public void testFinished(final Description description) throws Exception {
        final String uuid = testCases.get();
        testCases.remove();
        getLifecycle().updateTest(testResult -> {
            if (Objects.isNull(testResult.getStatus())) {
                testResult.setStatus(Status.PASSED);
            }
        });
        getLifecycle().stopTest();
        getLifecycle().writeTest(uuid);
    }

    @Override
    public void testFailure(final Failure failure) throws Exception {
        getLifecycle().updateTest(testResult -> testResult
                .setStatus(getStatus(failure.getException()).orElse(null))
                .setStatusMessage(failure.getMessage())
                .setStatusTrace(getStackTraceAsString(failure.getException()))
        );
    }

    @Override
    public void testAssumptionFailure(final Failure failure) {
        getLifecycle().updateTest(testResult -> testResult
                .setStatus(Status.SKIPPED)
                .setStatusMessage(failure.getMessage())
                .setStatusTrace(getStackTraceAsString(failure.getException()))
        );
    }

    @Override
    public void testIgnored(final Description description) throws Exception {
        final String uuid = testCases.get();
        testCases.remove();

        final TestResult result = createTestResult(uuid, description);
        result.setStatus(Status.SKIPPED);
        result.setStatusMessage(getIgnoredMessage(description));
        result.setStart(System.currentTimeMillis());

        getLifecycle().startTest(result);
        getLifecycle().stopTest();
        getLifecycle().writeTest(uuid);
    }

    private Optional<String> getDisplayName(final Description result) {
        return Optional.ofNullable(result.getAnnotation(DisplayName.class))
                .map(DisplayName::value);
    }

    private Optional<String> getDescription(final Description result) {
        return Optional.ofNullable(result.getAnnotation(io.qameta.allure.Description.class))
                .map(io.qameta.allure.Description::value);
    }

    private Set<Link> getLinks(final Description result) {
        return Stream.of(
                getAnnotationsOnClass(result, io.qameta.allure.Link.class).stream().map(ResultsUtils::createLink),
                getAnnotationsOnMethod(result, io.qameta.allure.Link.class).stream().map(ResultsUtils::createLink),
                getAnnotationsOnClass(result, io.qameta.allure.Issue.class).stream().map(ResultsUtils::createLink),
                getAnnotationsOnMethod(result, io.qameta.allure.Issue.class).stream().map(ResultsUtils::createLink),
                getAnnotationsOnClass(result, io.qameta.allure.TmsLink.class).stream().map(ResultsUtils::createLink),
                getAnnotationsOnMethod(result, io.qameta.allure.TmsLink.class).stream().map(ResultsUtils::createLink)
        ).reduce(Stream::concat).orElseGet(Stream::empty).collect(Collectors.toSet());
    }

    private List<Label> getLabels(final Description result) {
        return Stream.of(
                getLabels(result, Epic.class, ResultsUtils::createLabel),
                getLabels(result, Feature.class, ResultsUtils::createLabel),
                getLabels(result, Story.class, ResultsUtils::createLabel),
                getLabels(result, Severity.class, ResultsUtils::createLabel),
                getLabels(result, Owner.class, ResultsUtils::createLabel),
                getLabels(result, Tag.class, this::createLabel)
        ).reduce(Stream::concat).orElseGet(Stream::empty).collect(Collectors.toList());
    }

    private <T extends Annotation> Stream<Label> getLabels(final Description result, final Class<T> labelAnnotation,
                                                           final Function<T, Label> extractor) {

        final List<Label> labels = getAnnotationsOnMethod(result, labelAnnotation).stream()
                .map(extractor)
                .collect(Collectors.toList());

        if (labelAnnotation.isAnnotationPresent(Repeatable.class) || labels.isEmpty()) {
            final Stream<Label> onClassLabels = getAnnotationsOnClass(result, labelAnnotation).stream()
                    .map(extractor);
            labels.addAll(onClassLabels.collect(Collectors.toList()));
        }

        return labels.stream();
    }

    private Label createLabel(final Tag tag) {
        return new Label().setName("tag").setValue(tag.value());
    }

    private <T extends Annotation> List<T> getAnnotationsOnMethod(final Description result, final Class<T> clazz) {
        final T annotation = result.getAnnotation(clazz);
        return Stream.concat(
                extractRepeatable(result, clazz).stream(),
                Objects.isNull(annotation) ? Stream.empty() : Stream.of(annotation)
        ).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private <T extends Annotation> List<T> extractRepeatable(final Description result, final Class<T> clazz) {
        if (clazz != null && clazz.isAnnotationPresent(Repeatable.class)) {
            final Repeatable repeatable = clazz.getAnnotation(Repeatable.class);
            final Class<? extends Annotation> wrapper = repeatable.value();
            final Annotation annotation = result.getAnnotation(wrapper);
            if (Objects.nonNull(annotation)) {
                try {
                    final Method value = annotation.getClass().getMethod("value");
                    final Object annotations = value.invoke(annotation);
                    return Arrays.asList((T[]) annotations);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return Collections.emptyList();
    }

    private <T extends Annotation> List<T> getAnnotationsOnClass(final Description result, final Class<T> clazz) {
        return Stream.of(result)
                .map(Description::getTestClass)
                .filter(Objects::nonNull)
                .map(testClass -> testClass.getAnnotationsByType(clazz))
                .flatMap(Stream::of)
                .collect(Collectors.toList());
    }

    private String getHistoryId(final Description description) {
        return md5(description.getClassName() + description.getMethodName());
    }

    private String md5(final String source) {
        final byte[] bytes = getMessageDigest().digest(source.getBytes(UTF_8));
        return new BigInteger(1, bytes).toString(16);
    }

    private MessageDigest getMessageDigest() {
        try {
            return MessageDigest.getInstance(MD_5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not find md5 hashing algorithm", e);
        }
    }

    private String getPackage(final Class<?> testClass) {
        return Optional.ofNullable(testClass)
                .map(Class::getPackage)
                .map(Package::getName)
                .orElse("");
    }

    private String getIgnoredMessage(final Description description) {
        final Ignore ignore = description.getAnnotation(Ignore.class);
        final String message = Objects.nonNull(ignore) && !ignore.value().isEmpty()
                ? ignore.value() : "Test ignored (without reason)!";
        return message;
    }

    private TestResult createTestResult(final String uuid, final Description description) {
        final String testPackage = getPackage(description.getTestClass());
        final String className = description.getClassName();
        final String methodName = description.getMethodName();
        final String name = Objects.nonNull(methodName) ? methodName : className;
        final String fullName = Objects.nonNull(methodName) ? String.format("%s.%s", className, methodName) : className;
        final String suite = Optional.ofNullable(description.getTestClass())
                .map(it -> it.getAnnotation(DisplayName.class))
                .map(DisplayName::value).orElse(className);

        final TestResult testResult = new TestResult()
                .setUuid(uuid)
                .setHistoryId(getHistoryId(description))
                .setName(name)
                .setFullName(fullName)
                .setLinks(getLinks(description))
                .setLabels(getLabels(testPackage, className, name, suite));
        testResult.getLabels().addAll(getLabels(description));
        getDisplayName(description).ifPresent(testResult::setName);
        getDescription(description).ifPresent(testResult::setDescription);
        return testResult;
    }

    private Set<Label> getLabels(final String testPackage,
                                 final String className,
                                 final String name,
                                 final String suite) {
        return Stream.of(
                new Label().setName("package").setValue(testPackage),
                new Label().setName("testClass").setValue(className),
                new Label().setName("testMethod").setValue(name),
                new Label().setName("suite").setValue(suite),
                new Label().setName("host").setValue(getHostName()),
                new Label().setName("thread").setValue(getThreadName())
        ).collect(Collectors.toSet());
    }
}

