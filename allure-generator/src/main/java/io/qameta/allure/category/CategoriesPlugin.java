package io.qameta.allure.category;

import com.fasterxml.jackson.core.type.TypeReference;
import io.qameta.allure.CommonCsvExportAggregator;
import io.qameta.allure.CommonJsonAggregator;
import io.qameta.allure.CompositeAggregator;
import io.qameta.allure.Reader;
import io.qameta.allure.Widget;
import io.qameta.allure.context.JacksonContext;
import io.qameta.allure.core.Configuration;
import io.qameta.allure.core.LaunchResults;
import io.qameta.allure.core.ResultsVisitor;
import io.qameta.allure.csv.CsvExportCategory;
import io.qameta.allure.entity.Status;
import io.qameta.allure.entity.TestResult;
import io.qameta.allure.tree.DefaultTreeLayer;
import io.qameta.allure.tree.TestResultTree;
import io.qameta.allure.tree.TestResultTreeGroup;
import io.qameta.allure.tree.Tree;
import io.qameta.allure.tree.TreeLayer;
import io.qameta.allure.tree.TreeWidgetData;
import io.qameta.allure.tree.TreeWidgetItem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.qameta.allure.entity.Statistic.comparator;
import static io.qameta.allure.entity.TestResult.comparingByTimeAsc;
import static io.qameta.allure.tree.TreeUtils.calculateStatisticByLeafs;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Plugin that generates data for Categories tab.
 *
 * @since 2.0
 */
@SuppressWarnings("PMD.ExcessiveImports")
public class CategoriesPlugin extends CompositeAggregator implements Reader, Widget {

    public static final String CATEGORIES = "categories";

    public static final Category FAILED_TESTS = new Category().setName("Product defects");

    public static final Category BROKEN_TESTS = new Category().setName("Test defects");

    public static final String JSON_FILE_NAME = "categories.json";

    public static final String CSV_FILE_NAME = "categories.csv";

    //@formatter:off
    private static final TypeReference<List<Category>> CATEGORIES_TYPE =
        new TypeReference<List<Category>>() {};
    //@formatter:on

    public CategoriesPlugin() {
        super(Arrays.asList(new JsonAggregator(), new CsvExportAggregator()));
    }

    @Override
    public void readResults(final Configuration configuration,
                            final ResultsVisitor visitor,
                            final Path directory) {
        final JacksonContext context = configuration.requireContext(JacksonContext.class);
        final Path categoriesFile = directory.resolve(JSON_FILE_NAME);
        if (Files.exists(categoriesFile)) {
            try (InputStream is = Files.newInputStream(categoriesFile)) {
                final List<Category> categories = context.getValue().readValue(is, CATEGORIES_TYPE);
                visitor.visitExtra(CATEGORIES, categories);
            } catch (IOException e) {
                visitor.error("Could not read categories file " + categoriesFile, e);
            }
        }
    }

    @Override
    public String getName() {
        return CATEGORIES;
    }

    @Override
    public Object getData(final Configuration configuration, final List<LaunchResults> launches) {
        final Tree<TestResult> data = getData(launches);
        final List<TreeWidgetItem> items = data.getChildren().stream()
                .filter(TestResultTreeGroup.class::isInstance)
                .map(TestResultTreeGroup.class::cast)
                .map(CategoriesPlugin::toWidgetItem)
                .sorted(Comparator.comparing(TreeWidgetItem::getStatistic, comparator()).reversed())
                .limit(10)
                .collect(Collectors.toList());
        return new TreeWidgetData().setItems(items).setTotal(data.getChildren().size());
    }

    @SuppressWarnings("PMD.DefaultPackage")
    /* default */ static Tree<TestResult> getData(final List<LaunchResults> launchResults) {

        // @formatter:off
        final Tree<TestResult> categories = new TestResultTree(CATEGORIES, CategoriesPlugin::groupByCategories);
        // @formatter:on

        launchResults.stream()
                .map(LaunchResults::getResults)
                .flatMap(Collection::stream)
                .sorted(comparingByTimeAsc())
                .forEach(categories::add);
        return categories;
    }

    @SuppressWarnings("PMD.DefaultPackage")
    /* default */ static void addCategoriesForResults(final List<LaunchResults> launchesResults) {
        launchesResults.forEach(launch -> {
            final List<Category> categories = launch.getExtra(CATEGORIES, Collections::emptyList);
            launch.getResults().forEach(result -> {
                final List<Category> resultCategories = result.getExtraBlock(CATEGORIES, new ArrayList<>());
                categories.forEach(category -> {
                    if (matches(result, category)) {
                        resultCategories.add(category);
                    }
                });
                if (resultCategories.isEmpty() && Status.FAILED.equals(result.getStatus())) {
                    result.getExtraBlock(CATEGORIES, new ArrayList<Category>()).add(FAILED_TESTS);
                }
                if (resultCategories.isEmpty() && Status.BROKEN.equals(result.getStatus())) {
                    result.getExtraBlock(CATEGORIES, new ArrayList<Category>()).add(BROKEN_TESTS);
                }
            });
        });
    }

    protected static List<TreeLayer> groupByCategories(final TestResult testResult) {
        final Set<String> categories = testResult
                .<List<Category>>getExtraBlock(CATEGORIES, new ArrayList<>())
                .stream()
                .map(Category::getName)
                .collect(Collectors.toSet());
        final TreeLayer categoriesLayer = new DefaultTreeLayer(categories);
        final TreeLayer messageLayer = new DefaultTreeLayer(testResult.getStatusMessage().orElse("Without message"));
        return Arrays.asList(categoriesLayer, messageLayer);
    }

    public static boolean matches(final TestResult result, final Category category) {
        boolean matchesStatus = category.getMatchedStatuses().isEmpty()
                || nonNull(result.getStatus())
                && category.getMatchedStatuses().contains(result.getStatus());
        boolean matchesMessage = isNull(category.getMessageRegex())
                || nonNull(result.getStatusDetails())
                && nonNull(result.getStatusDetails().getMessage())
                && matches(result.getStatusDetails().getMessage(), category.getMessageRegex());
        boolean matchesTrace = isNull(category.getTraceRegex())
                || nonNull(result.getStatusDetails())
                && nonNull(result.getStatusDetails().getTrace())
                && matches(result.getStatusDetails().getTrace(), category.getTraceRegex());
        boolean matchesFlaky = nonNull(result.getStatusDetails())
                && result.getStatusDetails().isFlaky() == category.isFlaky();
        return matchesStatus && matchesMessage && matchesTrace && matchesFlaky;
    }

    private static boolean matches(final String message, final String pattern) {
        return Pattern.compile(pattern, Pattern.DOTALL).matcher(message).matches();
    }

    protected static TreeWidgetItem toWidgetItem(final TestResultTreeGroup group) {
        return new TreeWidgetItem()
                .setUid(group.getUid())
                .setName(group.getName())
                .setStatistic(calculateStatisticByLeafs(group));
    }

    @Override
    public void aggregate(final Configuration configuration,
                          final List<LaunchResults> launchesResults,
                          final Path outputDirectory) throws IOException {
        addCategoriesForResults(launchesResults);
        super.aggregate(configuration, launchesResults, outputDirectory);
    }

    private static class JsonAggregator extends CommonJsonAggregator {

        JsonAggregator() {
            super(JSON_FILE_NAME);
        }

        @Override
        protected Tree<TestResult> getData(final List<LaunchResults> launchResults) {
            return CategoriesPlugin.getData(launchResults);
        }
    }

    private static class CsvExportAggregator extends CommonCsvExportAggregator<CsvExportCategory> {

        CsvExportAggregator() {
            super(CSV_FILE_NAME, CsvExportCategory.class);
        }

        @Override
        protected List<CsvExportCategory> getData(final List<LaunchResults> launchesResults) {
            final List<CsvExportCategory> exportLabels = new ArrayList<>();
            final Tree<TestResult> data = CategoriesPlugin.getData(launchesResults);
            final List<TreeWidgetItem> items = data.getChildren().stream()
                    .filter(TestResultTreeGroup.class::isInstance)
                    .map(TestResultTreeGroup.class::cast)
                    .map(CategoriesPlugin::toWidgetItem)
                    .sorted(Comparator.comparing(TreeWidgetItem::getStatistic, comparator()).reversed())
                    .collect(Collectors.toList());
            items.forEach(item -> exportLabels.add(new CsvExportCategory(item)));
            return exportLabels;
        }
    }
}
