package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UserResponseModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NegotiationTicketBodyFormatterTest {

  @ParameterizedTest
  @CsvSource({
    "true, [NEGO-1] HIV Cohort Study — Dummy Collection",
    "false, [NEGO-1] Access Request — Dummy Collection"
  })
  void buildTitle_withResourceName_includesResourceNameInTitle(
      boolean payloadHasTitle, String expectedTitle) {
    assertThat(
            NegotiationTicketBodyFormatter.buildTitle(
                createNegotiation(payloadHasTitle), "Dummy Collection"))
        .isEqualTo(expectedTitle);
  }

  @ParameterizedTest
  @CsvSource({"true, [NEGO-1] HIV Cohort Study", "false, [NEGO-1] Resources Access Request"})
  void buildTitle_withNullResourceName_usesAggregateTitle(
      boolean payloadHasTitle, String expectedTitle) {
    assertThat(NegotiationTicketBodyFormatter.buildTitle(createNegotiation(payloadHasTitle), null))
        .isEqualTo(expectedTitle);
  }

  @Test
  void getTicketBodyWithSingleResource_rendersTitleAndRequesterDetails() {
    Document doc = renderSingle(createNegotiation(true), submittedResource());

    assertThat(doc.selectFirst("h2").text()).isEqualTo("Negotiator Access Request");
    assertThat(fieldValue(doc, "Requester")).isEqualTo("adam.researcher@gmail.com");
    assertThat(fieldValue(doc, "Email")).isEqualTo("adam.researcher@gmail.com");
    assertThat(fieldValue(doc, "Status")).isEqualTo("IN_PROGRESS");
    assertThat(fieldValue(doc, "Negotiation ID")).isEqualTo("1");
  }

  @Test
  void getTicketBodyWithSingleResource_rendersAllPayloadSectionHeaders() {
    Document doc = renderSingle(createNegotiation(true), submittedResource());

    assertThat(sectionHeaders(doc))
        .containsExactly(
            "PROJECT", "REQUEST", "ETHICS VOTE", "SEARCH PARAMETERS", "REQUESTED RESOURCE");
  }

  @Test
  void getTicketBodyWithSingleResource_rendersScalarPayloadFields() {
    Document doc = renderSingle(createNegotiation(true), submittedResource());

    assertThat(fieldValue(doc, "Title")).isEqualTo("HIV Cohort Study");
    assertThat(fieldValue(doc, "Disease Code")).isEqualTo("B23.0");
    assertThat(fieldValue(doc, "Objective"))
        .isEqualTo(
            "To investigate disease progression and comorbidities in people living with HIV/AIDS");
    assertThat(fieldValue(doc, "Organization")).isEqualTo("University Medical Center");
    assertThat(fieldValue(doc, "Profit")).isEqualTo("No");
    assertThat(fieldValue(doc, "Acknowledgment"))
        .isEqualTo("Funded by National Institute of Health Research");
    assertThat(fieldValue(doc, "Single Test")).isEqualTo("second_choice");
    assertThat(fieldValue(doc, "Bool Test")).isEqualTo("Yes");
  }

  @Test
  void getTicketBodyWithSingleResource_rendersListFieldAsBullets() {
    Document doc = renderSingle(createNegotiation(true), submittedResource());

    Element valueCell = fieldValueCell(doc, "Multichoice Test");
    Elements bullets = valueCell.select("ul > li");

    assertThat(bullets.eachText()).containsExactly("second_choice", "third_choice");
  }

  @Test
  void getTicketBodyWithSingleResource_rendersEmptyValuesAsDash() {
    Document doc = renderSingle(createNegotiation(true), submittedResource());

    // "collection" is an empty string, "ethics-vote-attachment" is null.
    assertThat(fieldValue(doc, "Collection")).isEqualTo("—");
    assertThat(fieldValue(doc, "Ethics Vote Attachment")).isEqualTo("—");
  }

  @Test
  void getTicketBodyWithSingleResource_rendersSearchParameters() {
    Document doc = renderSingle(createNegotiation(true), submittedResource());

    Element searchHeader = findSectionHeader(doc, "SEARCH PARAMETERS");
    Element paragraph = searchHeader.nextElementSibling();

    assertThat(paragraph.tagName()).isEqualTo("p");
    assertThat(paragraph.text()).isEqualTo("(diagnosis EQUALS B23.0)");
  }

  @Test
  void getTicketBodyWithSingleResource_rendersResourceBlock() {
    Document doc = renderSingle(createNegotiation(true), submittedResource());

    assertThat(fieldValue(doc, "Name")).isEqualTo("Dummy test collection");
    assertThat(fieldValue(doc, "ID")).isEqualTo("bbmri-eric:ID:SE_890:collection:dummy_collection");
    assertThat(allFieldValues(doc, "Status")).contains("IN_PROGRESS", "SUBMITTED");
  }

  @Test
  void getTicketBodyWithMultipleResources_rendersEachResourceWithId() {
    var negotiation = createNegotiation(true);
    var r1 =
        createResource("Dummy test collection", "bbmri-eric:ID:SE_890:collection:dummy_collection");
    var r2 =
        createResource(
            "Dummy test collection2", "bbmri-eric:ID:SE_890:collection:dummy_collection2");

    Document doc =
        Jsoup.parse(
            NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(
                negotiation, List.of(r1, r2)));

    Element resourcesHeader = findSectionHeader(doc, "REQUESTED RESOURCES");
    Element list = resourcesHeader.nextElementSibling();
    Elements items = list.select("> li");

    assertThat(items).hasSize(2);
    assertThat(items.get(0).selectFirst("strong").text()).isEqualTo("Dummy test collection");
    assertThat(items.get(0).text()).contains("bbmri-eric:ID:SE_890:collection:dummy_collection");
    assertThat(items.get(1).selectFirst("strong").text()).isEqualTo("Dummy test collection2");
    assertThat(items.get(1).text()).contains("bbmri-eric:ID:SE_890:collection:dummy_collection2");
  }

  @Test
  void getTicketBodyWithMultipleResources_rendersSharedStatus() {
    var negotiation = createNegotiation(true);
    var r1 = createResource("A", "id-a");
    var r2 = createResource("B", "id-b");

    Document doc =
        Jsoup.parse(
            NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(
                negotiation, List.of(r1, r2)));

    assertThat(fieldValue(doc, "Status (all resources)")).isEqualTo("SUBMITTED");
  }

  @Test
  void getTicketBodyWithSingleResource_escapesHtmlInPayloadValues() {
    var negotiation = createNegotiation(true);
    @SuppressWarnings("unchecked")
    Map<String, Object> project =
        (Map<String, Object>) ((Map<String, Object>) negotiation.getPayload()).get("project");
    project.put("organization", "<script>alert('xss')</script>");

    String rawHtml =
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
            negotiation, submittedResource());

    // The dangerous markup must NOT appear as a live tag ...
    assertThat(rawHtml).doesNotContain("<script>");
    // ... but the text content must be preserved (escaped).
    assertThat(rawHtml).contains("&lt;script&gt;");

    // And when parsed, the value cell contains the literal text, no script element.
    Document doc = Jsoup.parse(rawHtml);
    assertThat(fieldValue(doc, "Organization")).isEqualTo("<script>alert('xss')</script>");
    assertThat(doc.select("script")).isEmpty();
  }

  @Test
  void getTicketBodyWithSingleResource_escapesHtmlInResourceName() {
    var negotiation = createNegotiation(true);
    var resource = createResource("<b>Evil</b>", "id-x");

    String rawHtml =
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(negotiation, resource);

    assertThat(rawHtml).doesNotContain("<b>Evil</b>");
    assertThat(Jsoup.parse(rawHtml).getElementsContainingOwnText("<b>Evil</b>")).isNotEmpty();
  }

  @Test
  void getTicketBodyWithSingleResource_usesInlineStylesAndNoStyleBlock() {
    String rawHtml =
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
            createNegotiation(true), submittedResource());
    Document doc = Jsoup.parse(rawHtml);

    assertThat(doc.select("style")).isEmpty();
    assertThat(doc.selectFirst("div").hasAttr("style")).isTrue();
    assertThat(doc.selectFirst("h2").hasAttr("style")).isTrue();
  }

  @Test
  void getUpdatedTicketBodyWithSingleResource_prependsUpdateNote() {
    String html =
        NegotiationTicketBodyFormatter.getUpdatedTicketBodyWithSingleResource(
            createNegotiation(true), submittedResource());

    assertThat(html).startsWith("<p><strong>");
    assertThat(html).contains("updated");
    assertThat(html).contains("Negotiator Access Request");
  }

  @Test
  void getUpdatedTicketBodyWithMultipleResources_prependsUpdateNote() {
    var r1 = createResource("A", "id-a");
    var r2 = createResource("B", "id-b");

    String html =
        NegotiationTicketBodyFormatter.getUpdatedTicketBodyWithMultipleResources(
            createNegotiation(true), List.of(r1, r2));

    assertThat(html).startsWith("<p><strong>");
    assertThat(html).contains("updated");
    assertThat(html).contains("Negotiator Access Request");
  }

  // ---------------------------------------------------------------------------
  // Jsoup helpers
  // ---------------------------------------------------------------------------

  private static Document renderSingle(
      EntityModelNegotiationDTO negotiation, EntityModelResourceWithStatusDTO resource) {
    return Jsoup.parse(
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(negotiation, resource));
  }

  /** Returns the value-cell text for the first row whose label matches. */
  private static String fieldValue(Document doc, String label) {
    return fieldValueCell(doc, label).text();
  }

  /** Returns the value cell (2nd &lt;td&gt;) of the first row whose 1st &lt;td&gt; equals label. */
  private static Element fieldValueCell(Document doc, String label) {
    for (Element row : doc.select("table tr")) {
      Elements cells = row.select("> td");
      if (cells.size() == 2 && cells.get(0).text().equals(label)) {
        return cells.get(1);
      }
    }
    throw new AssertionError("No field row found with label: " + label);
  }

  /** All value-cell texts for every row matching the label (labels can repeat, e.g. "Status"). */
  private static List<String> allFieldValues(Document doc, String label) {
    return doc.select("table tr").stream()
        .map(row -> row.select("> td"))
        .filter(cells -> cells.size() == 2 && cells.get(0).text().equals(label))
        .map(cells -> cells.get(1).text())
        .toList();
  }

  private static List<String> sectionHeaders(Document doc) {
    return doc.select("h3").eachText();
  }

  private static Element findSectionHeader(Document doc, String title) {
    return doc.select("h3").stream()
        .filter(h -> h.text().equals(title))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No section header: " + title));
  }

  private EntityModelResourceWithStatusDTO submittedResource() {
    return createResource(
        "Dummy test collection", "bbmri-eric:ID:SE_890:collection:dummy_collection");
  }

  private EntityModelNegotiationDTO createNegotiation(boolean hasTitle) {
    var author = new UserResponseModel();
    author.setName("adam.researcher@gmail.com");
    author.setEmail("adam.researcher@gmail.com");

    Map<String, Object> project = new LinkedHashMap<>();
    if (hasTitle) {
      project.put("title", "HIV Cohort Study");
    }
    project.put("disease-code", "B23.0");
    project.put(
        "objective",
        "To investigate disease progression and comorbidities in people living with HIV/AIDS");
    project.put("organization", "University Medical Center");
    project.put("profit", "No");
    project.put("acknowledgment", "Funded by National Institute of Health Research");
    project.put("Multichoice test", List.of("second_choice", "third_choice"));
    project.put("Single test", "second_choice");
    project.put("Bool test", "Yes");

    Map<String, Object> request = new LinkedHashMap<>();
    request.put(
        "description",
        "We seek access to HIV cohort samples with documented disease progression markers and comorbidity data");
    request.put("collection", "");
    request.put("donors", "150");
    request.put("samples", "serum");
    request.put("specifics", "CD4 count and viral load data required");

    Map<String, Object> ethicsVote = new LinkedHashMap<>();
    ethicsVote.put("ethics-vote", "Approved by University Ethics Committee");
    ethicsVote.put("ethics-vote-attachment", null);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("project", project);
    payload.put("request", request);
    payload.put("ethics-vote", ethicsVote);

    var negotiation = new EntityModelNegotiationDTO();
    negotiation.setDisplayId("1");
    negotiation.setStatus("IN_PROGRESS");
    negotiation.setAuthor(author);
    negotiation.setHumanReadable("(diagnosis EQUALS B23.0)");
    negotiation.setPayload(payload);
    return negotiation;
  }

  private EntityModelResourceWithStatusDTO createResource(String name, String sourceId) {
    var resource = new EntityModelResourceWithStatusDTO();
    resource.setName(name);
    resource.setSourceId(sourceId);
    resource.setCurrentState(
        EntityModelResourceWithStatusDTO.CurrentStateEnum.fromValue("SUBMITTED"));
    return resource;
  }
}
