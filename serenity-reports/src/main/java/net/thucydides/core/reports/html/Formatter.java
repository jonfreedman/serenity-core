package net.thucydides.core.reports.html;

import com.github.rjeschke.txtmark.Configuration;
import com.google.common.base.Splitter;
import com.google.inject.Inject;
import com.google.inject.Key;
import com.vladsch.flexmark.ast.Node;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.options.MutableDataSet;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.model.TestOutcome;
import net.thucydides.core.reports.renderer.Asciidoc;
import net.thucydides.core.reports.renderer.MarkupRenderer;
import net.thucydides.core.requirements.reports.RenderMarkdown;
import net.thucydides.core.requirements.reports.RequirementsOutcomes;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.core.util.Inflection;
import net.thucydides.core.util.Inflector;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.translate.AggregateTranslator;
import org.apache.commons.lang3.text.translate.CharSequenceTranslator;
import org.apache.commons.lang3.text.translate.EntityArrays;
import org.apache.commons.lang3.text.translate.LookupTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.*;

//////

/**
 * Format text for HTML reports.
 * In particular, this integrates JIRA links into the generated reports.
 */
public class Formatter  {


    private static final String ELIPSE = "&hellip;";
    private static final String ASCIIDOC = "asciidoc";
    private static final String MARKDOWN = "markdown";
    private static final String TEXT = "";
    private static final String STANDARD_NEW_LINE = "\n";

    private final static String NEWLINE_CHAR = "\u2424";
    private final static String NEWLINE = "\u0085";
    private final static String LINE_SEPARATOR = "\u2028";
    private final static String PARAGRAPH_SEPARATOR = "\u2029";

    private final static Logger LOGGER = LoggerFactory.getLogger(Formatter.class);
    public static final String FOUR_SPACES = "&nbsp; &nbsp; &nbsp; &nbsp;";
    public static final String TAB = "\\t";
    public static final String NEW_LINE_ON_ANY_OS = "\\r?\\n";
    public static final String UTF_8_NEW_LINE = "␤";

    public static String[][] UNICODE_CHARS_ESCAPE = new String[][]{{"\\u", "&#92;"}};

    private final EnvironmentVariables environmentVariables;
    private final MarkupRenderer asciidocRenderer;
    Configuration markdownEncodingConfiguration;

    Parser parser;
    HtmlRenderer renderer;

    @Inject
    public Formatter(EnvironmentVariables environmentVariables) {
        this.environmentVariables = environmentVariables;
        this.asciidocRenderer = Injectors.getInjector().getInstance(Key.get(MarkupRenderer.class, Asciidoc.class));

        String encoding = ThucydidesSystemProperty.REPORT_CHARSET.from(environmentVariables,"UTF-8");
        markdownEncodingConfiguration = Configuration.builder().setEncoding(encoding).build();


        /////////////
        MutableDataSet options = new MutableDataSet();

        // uncomment to set optional extensions
        options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create(), StrikethroughExtension.create()));

        // uncomment to convert soft-breaks to hard breaks
        //options.set(HtmlRenderer.SOFT_BREAK, "<br />\n");

        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();

    }

    public Formatter() {
        this(Injectors.getInjector().getProvider(EnvironmentVariables.class).get() );
    }

    public String renderAsciidoc(String text) {
        return stripNewLines(asciidocRenderer.render(text));
    }

    public String renderMarkdown(String text) {
        if (text == null) { return ""; }

        Node document = parser.parse(text);
        String html = renderer.render(document);

        return stripSurroundingParagraphTagsFrom(html);
    }

    private String stripSurroundingParagraphTagsFrom(String text) {
        if (startsWithParagraphTag(text) && endWithParagraphTag(text)) {
            text = trim(text).substring(3);
            text = text.substring(0, text.length() - 4);
        }
        return text;
    }

    private boolean startsWithParagraphTag(String text) {
        return trim(text.toLowerCase()).startsWith("<p>");
    }

    private boolean endWithParagraphTag(String text) {
        return trim(text.toLowerCase()).endsWith("</p>");
    }

    private String stripNewLines(String render) {
        return (render != null) ? render.replaceAll("\n", "") : "";
    }


    public String escapedXML(String text) {
        return BASIC_XML.translate(text);
    }
    public String renderText(String text) {
        if (isEmpty(text)) {
            return "";
        }

        return concatLines(BASIC_XML.translate(text),"<br>")
                .replaceAll(TAB, FOUR_SPACES);
    }

    public String renderHeaders(String text) {
        if (text == null) {
            return "";
        }
        return concatLines(BASIC_XML.translate(stringFormOf(text)),"<br>")
                .replaceAll("\\t", "");
    }


    public String renderHtmlEscapedDescription(final String text) {
        return renderDescription(RenderMarkdown.preprocessMarkdownTables(withEscapedParameterFields(text)));
    }

    public String renderDescription(final String text) {
        if (text == null) { return ""; }

        String format = environmentVariables.getProperty(ThucydidesSystemProperty.NARRATIVE_FORMAT, TEXT);

        if (isRenderedHtml(text)) {
            return text;
        } else if (format.equalsIgnoreCase(ASCIIDOC)) {  // Use ASCIIDOC if configured
            return renderAsciidoc(text.trim());
        } else if (format.equalsIgnoreCase(MARKDOWN) ||  (MarkdownRendering.configuredIn(environmentVariables).renderMarkdownFor(MarkdownRendering.RenderedElements.narrative)) ) {
            return renderMarkdown(text.trim());
        } else {
            return addLineBreaks(text);
        }
    }

    private static String withEscapedParameterFields(String text) {
        return text.replace("<","{").replace(">","}");
    }

    private String convertTablesToMarkdown(String text) {
        return MarkdownTables.convertTablesIn(text);
    }

    public String renderDescriptionWithEmbeddedResults(final String text, RequirementsOutcomes requirementsOutcomes) {

        String textWithResults = RenderMarkdown.preprocessMarkdownTables(
                                                textWithEmbeddedExampleResults(textWithEmbeddedResults(text, requirementsOutcomes), requirementsOutcomes));
        return wrapTablesInDivs(renderDescription(textWithResults),"example-table example-table-in-summary");
    }

    public String renderTableDescription(final String text, RequirementsOutcomes requirementsOutcomes) {
        String textWithResults = textWithEmbeddedExampleResults(textWithEmbeddedResults(text, requirementsOutcomes), requirementsOutcomes);
        return wrapTablesInDivs(renderDescription(textWithResults),"example-table-in-scenario");
    }

    private final Pattern RESULT_TOKEN = Pattern.compile("\\{result:(.*)!(.*)\\}'?");
    private final Pattern EXAMPLE_RESULT_TOKEN = Pattern.compile("\\{example-result:(.*)!(.*)\\[(.*)\\]\\[(.*)\\]\\}'?");

    private String textWithEmbeddedResults(String text, RequirementsOutcomes requirementsOutcomes) {

        ResultIconFormatter resultIconFormatter = new ResultIconFormatter();

        StringBuffer newText = new StringBuffer();
        Matcher matcher = RESULT_TOKEN.matcher(text);
        while (matcher.find()) {
            String feature= matcher.group(1);
            String scenario= matcher.group(2);

            Optional<? extends TestOutcome> matchingOutcome = requirementsOutcomes.getTestOutcomes().getOutcomes().stream().filter(
                                                     outcome -> outcome.getName().equalsIgnoreCase(scenario)
                                                                && outcome.getUserStory().getName().equalsIgnoreCase(feature)
            ).findFirst();


            matchingOutcome.ifPresent(testOutcome -> matcher.appendReplacement(newText, resultIconFormatter.forResult(testOutcome.getResult())));
        }
        matcher.appendTail(newText);

        return newText.toString();
    }

    private String wrapTablesInDivs(String markdownText, String cssClass) {
        return markdownText.replace("<table>","<div class='" + cssClass + "'><table>")
                           .replace("</table>", "</table></div>");
    }

    private String textWithEmbeddedExampleResults(String text, RequirementsOutcomes requirementsOutcomes) {

        ResultIconFormatter resultIconFormatter = new ResultIconFormatter();

        StringBuffer newText = new StringBuffer();
        Matcher matcher = EXAMPLE_RESULT_TOKEN.matcher(text);
        while (matcher.find()) {
            String feature= matcher.group(1);
            String scenario= matcher.group(2);
            int exampleTable = Integer.parseInt(matcher.group(3));
            int exampleRow = Integer.parseInt(matcher.group(4));

            Optional<? extends TestOutcome> matchingOutcome = requirementsOutcomes.getTestOutcomes().getOutcomes().stream().filter(
                    outcome -> outcome.getName().equalsIgnoreCase(scenario) && outcome.getUserStory().getName().equalsIgnoreCase(feature)
            ).findFirst();

            matchingOutcome.ifPresent(
                    testOutcome -> {

                        int dataRow = exampleRow;
                        if (exampleTable > 0) {
                            dataRow = testOutcome.getDataTable().getDataSetDescriptors().get(exampleTable - 1).getStartRow()
                                      + testOutcome.getDataTable().getDataSetDescriptors().get(exampleTable - 1).getRowCount()
                                      + exampleRow;
                        }

                        if (dataRow < testOutcome.getTestSteps().size()) {
                            matcher.appendReplacement(newText,
                                                      resultIconFormatter.forResult(testOutcome.getTestSteps().get(dataRow).getResult()));
                        } else {
                            matcher.appendReplacement(newText,"");
                        }
                    });
        }
        matcher.appendTail(newText);
        return newText.toString();
    }

    private boolean isRenderedHtml(String text) {
        return (text != null) && (text.startsWith("<"));
    }

    public static String addLineBreaks(final String text) {
        return (text != null) ? concatLines(text.trim(), "<br>") : "";
    }

    public static String addMarkdownLineBreaks(final String text) {
        return (text != null) ? concatLines(text.trim(), "  ") : "";
    }

    public String convertAnyTables(String text) {
        if (shouldFormatEmbeddedTables() && containsEmbeddedTable(text)) {
            text = convertNonStandardNLChars(text);
            text = ExampleTable.stripBracketsFromOuterPipes(text);
            text = withTablesReplaced(text);

        }
        return text;
    }

    private String withTablesReplaced(String text) {
        List<String> unformattedTables = getEmbeddedTablesIn(text);
        for(String unformattedTable : unformattedTables) {
            ExampleTable table = new ExampleTable(unformattedTable);

            text = text.replace(unformattedTable, table.inHtmlFormat());
        }
        text = text.replaceAll(newLineUsedIn(text), "<br>");
        return text;
    }

    private String convertNonStandardNLChars(String text) {
        text = StringUtils.replace(text, NEWLINE_CHAR, STANDARD_NEW_LINE);
        text = StringUtils.replace(text, NEWLINE, STANDARD_NEW_LINE);
        text = StringUtils.replace(text, LINE_SEPARATOR, STANDARD_NEW_LINE);
        text = StringUtils.replace(text, PARAGRAPH_SEPARATOR, STANDARD_NEW_LINE);
        return text;
    }

    private boolean shouldFormatEmbeddedTables() {
        return !(ThucydidesSystemProperty.IGNORE_EMBEDDED_TABLES.booleanFrom(environmentVariables));
    }

    private boolean containsEmbeddedTable(String text) {
        return ((positionOfFirstPipeIn(text) >= 0)  && (positionOfLastPipeIn(text) >= 0));
    }

    private int positionOfLastPipeIn(String text) {
        return text.indexOf("|", positionOfFirstPipeIn(text) + 1);
    }

    private int positionOfFirstPipeIn(String text) {
        return text.indexOf("|");
    }

    private List<String> getEmbeddedTablesIn(String text) {
        List<String> embeddedTables = new ArrayList<>();
        StringBuffer tableText = new StringBuffer();
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            boolean inTable = false;
            String newLine = newLineUsedIn(text);
            String line;
            while ((line = reader.readLine()) != null) {
                if (!inTable && line.contains("|")){ // start of a table
                    inTable = true;
                } else if (inTable && !line.contains("|") && !(isBlank(line))){ // end of a table
                    embeddedTables.add(tableText.toString().trim());
                    tableText = new StringBuffer();
                    inTable = false;
                }
                if (inTable) {
                    tableText.append(line).append(newLine);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not process embedded table", e);
        }

        if (!tableText.toString().isEmpty()) {
            embeddedTables.add(tableText.toString().trim());
        }
        return embeddedTables;

    }

    private boolean isBlank(String line) {
        return (StringUtils.isBlank(line.trim()));
    }

    private String newLineUsedIn(String text) {
        if (text.contains("\r\n")) {
            return "\r\n";
        } else if (text.contains("\n")) {
            return "\n";
        } else if (text.contains("\r")) {
            return "\r";
        } else {
            return STANDARD_NEW_LINE;
        }
    }

    private static final CharSequenceTranslator ESCAPE_SPECIAL_CHARS = new AggregateTranslator(
            new LookupTranslator(EntityArrays.ISO8859_1_ESCAPE()),
            new LookupTranslator(EntityArrays.HTML40_EXTENDED_ESCAPE())
    );

    private final CharSequenceTranslator BASIC_XML = new AggregateTranslator(
            new LookupTranslator(EntityArrays.BASIC_ESCAPE()),
            new LookupTranslator(UNICODE_CHARS_ESCAPE)
    );

    public String htmlCompatible(Object fieldValue) {
        return plainHtmlCompatible(fieldValue);
    }

    public String messageBody(String message) {
        return renderText(message.trim());
    }

    public String restQuery(String message) {
        return renderText(message.trim());
    }

    public String htmlCompatibleStoryTitle(Object fieldValue) {
        String firstLine = fieldValue.toString().split("\\n")[0];

        return (MarkdownRendering.configuredIn(environmentVariables).renderMarkdownFor(MarkdownRendering.RenderedElements.story)) ?
                (htmlCompatible(renderMarkdown(firstLine))) : htmlCompatible(firstLine);
    }

    public String htmlCompatibleTestTitle(Object fieldValue) {
        String firstLine = fieldValue.toString().split("\\n")[0];

        return (MarkdownRendering.configuredIn(environmentVariables).renderMarkdownFor(MarkdownRendering.RenderedElements.scenario)) ?
                (htmlCompatible(renderMarkdown(firstLine))) : htmlCompatible(firstLine);
    }

    public String htmlCompatibleStepDescription(Object fieldValue) {
        return (MarkdownRendering.configuredIn(environmentVariables).renderMarkdownFor(MarkdownRendering.RenderedElements.step)) ?
                (htmlCompatible(renderMarkdown(fieldValue.toString()))) : htmlCompatible(fieldValue);
    }

    public String plainHtmlCompatible(Object fieldValue) {
        return addLineBreaks(ESCAPE_SPECIAL_CHARS.translate(fieldValue != null ? stringFormOf(fieldValue) : "")).trim();
    }

    public String htmlAttributeCompatible(Object fieldValue) {
        return htmlAttributeCompatible(fieldValue, false);
    }

    public String htmlAttributeCompatible(Object fieldValue, boolean renderNewLines) {
        if (fieldValue == null) { return ""; }

        return concatLines(ESCAPE_SPECIAL_CHARS.translate(stringFormOf(fieldValue)
                .replaceAll("<", "(")
                .replaceAll(">", ")")
                .replaceAll("\"", "'")), "<br>");
    }

    public String htmlAttributeCompatible(Object fieldValue, int maxLength) {
        return abbreviate(htmlAttributeCompatible(fieldValue), maxLength);
    }

    public ResultIconFormatter resultIcon() {
        return new ResultIconFormatter();
    }

    public ResultRankingFormatter resultRank() {
        return new ResultRankingFormatter();
    }

    private static String concatLines(String message) {
        return concatLines(message," ");
    }

    private static String concatLines(String message, String newLine) {
        message = StringUtils.replace(message, UTF_8_NEW_LINE, newLine);
        List<String> lines = Splitter.onPattern(NEW_LINE_ON_ANY_OS).splitToList(message);
        return StringUtils.join(lines,newLine);
    }

    private static String stringFormOf(Object fieldValue) {
        if (Iterable.class.isAssignableFrom(fieldValue.getClass())) {
            return "[" + StringUtils.join((Iterable)fieldValue, ", ") +"]";
        } else {
            return fieldValue.toString();
        }
    }

    public String truncatedHtmlCompatible(String text, int length) {
        return htmlCompatible(text);
    }

    public String humanReadableFormOf(String text) {
        return Inflector.inflection().humanize(text);
    }

    public String formatWithFields(String textToFormat) {
        String textWithEscapedFields  = textToFormat.replaceAll("<", "&lt;")
                                                    .replaceAll(">", "&gt;");

        String renderedText = addLineBreaks(removeMacros(convertAnyTables(textWithEscapedFields)));
        if (MarkdownRendering.configuredIn(environmentVariables).renderMarkdownFor(MarkdownRendering.RenderedElements.step)) {
            renderedText = renderMarkdown(renderedText);
        }
        return renderedText;

    }

    private String removeMacros(String textToFormat) {
        return textToFormat.replaceAll("\\{trim=false\\}\\s*\\r?\\n","");
    }

}
