package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO.CurrentStateEnum;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.OrganizationDTO;
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

  private static final CurrentStateEnum SUBMITTED = CurrentStateEnum.SUBMITTED;
  private static final CurrentStateEnum RESOURCE_AVAILABLE = CurrentStateEnum.RESOURCE_AVAILABLE;

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
    assertThat(fieldValue(doc, "State")).isEqualTo("IN_PROGRESS");
    assertThat(fieldValue(doc, "Negotiation ID")).isEqualTo("1");
  }

  @Test
  void getTicketBodyWithSingleResource_rendersAllPayloadSectionHeaders() {
    Document doc = renderSingle(createNegotiation(true), submittedResource());

    assertThat(sectionHeaders(doc))
        .containsExactly(
            "PROJECT", "REQUEST", "ETHICS VOTE", "SEARCH PARAMETERS", "REQUESTED RESOURCES");
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
    // Two "State" labels exist (negotiation + resource); assert the resource one is present.
    assertThat(allFieldValues(doc, "State")).contains("IN_PROGRESS", "SUBMITTED");
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
                negotiation, List.of(r1, r2), List.of(r1, r2)));

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
  void getTicketBodyWithMultipleResources_rendersSharedState() {
    var negotiation = createNegotiation(true);
    var r1 = createResource("A", "id-a");
    var r2 = createResource("B", "id-b");

    Document doc =
        Jsoup.parse(
            NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(
                negotiation, List.of(r1, r2), List.of(r1, r2)));

    assertThat(fieldValue(doc, "State (all resources)")).isEqualTo("SUBMITTED");
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
            negotiation, submittedResource(), List.of(submittedResource()));

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
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
            negotiation, resource, List.of(resource));

    assertThat(rawHtml).doesNotContain("<b>Evil</b>");
    assertThat(Jsoup.parse(rawHtml).getElementsContainingOwnText("<b>Evil</b>")).isNotEmpty();
  }

  @Test
  void getTicketBodyWithSingleResource_usesInlineStylesAndNoStyleBlock() {
    String rawHtml =
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
            createNegotiation(true), submittedResource(), List.of(submittedResource()));
    Document doc = Jsoup.parse(rawHtml);

    assertThat(doc.select("style")).isEmpty();
    assertThat(doc.selectFirst("div").hasAttr("style")).isTrue();
    assertThat(doc.selectFirst("h2").hasAttr("style")).isTrue();
  }

  @Test
  void getUpdatedTicketBodyWithSingleResource_prependsUpdateNote() {
    String html =
        NegotiationTicketBodyFormatter.getUpdatedTicketBodyWithSingleResource(
            createNegotiation(true), submittedResource(), List.of(submittedResource()));

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
            createNegotiation(true), List.of(r1, r2), List.of(r1, r2));

    assertThat(html).startsWith("<p><strong>");
    assertThat(html).contains("updated");
    assertThat(html).contains("Negotiator Access Request");
  }

  // ---------------------------------------------------------------------------
  // Other resources section
  // ---------------------------------------------------------------------------

  @Test
  void otherResourcesSection_whenOtherOrgResourcesExist_groupsByOrgName() {
    var negotiation = createNegotiation(true);
    var ticketResource = createResource(1L, "Munich Blood", "id-1", "Biobank Munich", SUBMITTED);
    var otherA1 = createResource(2L, "Prague Tissue", "id-2", "Biobank Prague", RESOURCE_AVAILABLE);
    var otherA2 = createResource(3L, "Prague Serum", "id-3", "Biobank Prague", SUBMITTED);
    var otherB = createResource(4L, "Vienna Cohort", "id-4", "Biobank Vienna", SUBMITTED);

    Document doc =
        Jsoup.parse(
            NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
                negotiation, ticketResource, List.of(ticketResource, otherA1, otherA2, otherB)));

    assertThat(sectionHeaders(doc)).contains("OTHER RESOURCES IN THIS NEGOTIATION");
    assertThat(doc.select("h4").eachText()).containsExactly("Biobank Prague", "Biobank Vienna");
    assertThat(otherResourcesUnder(doc, "Biobank Prague"))
        .containsExactly("Prague Tissue", "Prague Serum");
    assertThat(otherResourcesUnder(doc, "Biobank Vienna")).containsExactly("Vienna Cohort");
  }

  @Test
  void otherResourcesSection_whenNoOtherResources_omitsSection() {
    var negotiation = createNegotiation(true);
    var ticketResource = createResource(1L, "Munich Blood", "id-1", "Biobank Munich", SUBMITTED);

    Document doc =
        Jsoup.parse(
            NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
                negotiation, ticketResource, List.of(ticketResource)));

    assertThat(sectionHeaders(doc)).doesNotContain("OTHER RESOURCES IN THIS NEGOTIATION");
    assertThat(doc.select("h4")).isEmpty();
  }

  @Test
  void otherResourcesSection_whenOtherResourcePresent_showsNameStateAndSourceId() {
    var negotiation = createNegotiation(true);
    var ticketResource = createResource(1L, "Munich Blood", "id-1", "Biobank Munich", SUBMITTED);
    var other =
        createResource(2L, "Prague Tissue", "src-prague", "Biobank Prague", RESOURCE_AVAILABLE);

    Document doc =
        Jsoup.parse(
            NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
                negotiation, ticketResource, List.of(ticketResource, other)));

    Element item = otherResourceItem(doc, "Biobank Prague", 0);
    assertThat(item.selectFirst("strong").text()).isEqualTo("Prague Tissue");
    assertThat(item.text()).contains("State: RESOURCE_AVAILABLE");
    assertThat(item.text()).contains("ID: src-prague");
  }

  @Test
  void otherResourcesSection_whenOtherResourceHasNullOrg_groupsUnderUnknownOrganization() {
    var negotiation = createNegotiation(true);
    var ticketResource = createResource(1L, "Munich Blood", "id-1", "Biobank Munich", SUBMITTED);
    var other = createResource(2L, "Orphan Resource", "id-2", null, SUBMITTED);

    Document doc =
        Jsoup.parse(
            NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
                negotiation, ticketResource, List.of(ticketResource, other)));

    assertThat(doc.select("h4").eachText()).containsExactly("Unknown organization");
    assertThat(otherResourcesUnder(doc, "Unknown organization")).containsExactly("Orphan Resource");
  }

  @Test
  void otherResourcesSection_whenOtherResourceNameHasHtml_escapesIt() {
    var negotiation = createNegotiation(true);
    var ticketResource = createResource(1L, "Munich Blood", "id-1", "Biobank Munich", SUBMITTED);
    var other = createResource(2L, "<b>Evil</b>", "id-2", "Biobank Prague", SUBMITTED);

    String rawHtml =
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
            negotiation, ticketResource, List.of(ticketResource, other));

    assertThat(rawHtml).doesNotContain("<b>Evil</b>");
    assertThat(Jsoup.parse(rawHtml).getElementsContainingOwnText("<b>Evil</b>")).isNotEmpty();
  }

  @Test
  void otherResourcesSection_whenSameOrgHasSiblingResources_listsThemUnderSameOrg() {
    var negotiation = createNegotiation(true);
    // PER_RESOURCE: one ticket shows resource A; B and C are siblings from the SAME organization.
    var ticketResource = createResource(1L, "Resource A", "id-a", "Biobank Munich", SUBMITTED);
    var siblingB = createResource(2L, "Resource B", "id-b", "Biobank Munich", RESOURCE_AVAILABLE);
    var siblingC = createResource(3L, "Resource C", "id-c", "Biobank Munich", SUBMITTED);

    Document doc =
        Jsoup.parse(
            NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
                negotiation, ticketResource, List.of(ticketResource, siblingB, siblingC)));

    assertThat(sectionHeaders(doc)).contains("OTHER RESOURCES IN THIS NEGOTIATION");
    assertThat(doc.select("h4").eachText()).containsExactly("Biobank Munich");
    assertThat(otherResourcesUnder(doc, "Biobank Munich"))
        .containsExactly("Resource B", "Resource C");
  }

  @Test
  void otherResourcesSection_whenMatchedById_excludesTicketResourcesWithSameSourceId() {
    var negotiation = createNegotiation(true);
    var ticketResource =
        createResource(1L, "Shared Name", "shared-src", "Biobank Munich", SUBMITTED);
    var other = createResource(2L, "Shared Name", "shared-src", "Biobank Prague", SUBMITTED);

    Document doc =
        Jsoup.parse(
            NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
                negotiation, ticketResource, List.of(ticketResource, other)));

    assertThat(doc.select("h4").eachText()).containsExactly("Biobank Prague");
    assertThat(otherResourcesUnder(doc, "Biobank Prague")).containsExactly("Shared Name");
  }

  // ---------------------------------------------------------------------------
  // Jsoup helpers
  // ---------------------------------------------------------------------------

  private static Document renderSingle(
      EntityModelNegotiationDTO negotiation, EntityModelResourceWithStatusDTO resource) {
    return Jsoup.parse(
        NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(
            negotiation, resource, List.of(resource)));
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

  /** All value-cell texts for every row matching the label (labels can repeat, e.g. "State"). */
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

  /** Resource names listed under the given "other resources" organization subheading (h4). */
  private static List<String> otherResourcesUnder(Document doc, String organizationName) {
    return organizationList(doc, organizationName).select("> li strong").eachText();
  }

  /** The nth resource &lt;li&gt; under the given organization subheading. */
  private static Element otherResourceItem(Document doc, String organizationName, int index) {
    return organizationList(doc, organizationName).select("> li").get(index);
  }

  private static Element organizationList(Document doc, String organizationName) {
    Element subheading =
        doc.select("h4").stream()
            .filter(h -> h.text().equals(organizationName))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("No organization subheading: " + organizationName));
    return subheading.nextElementSibling();
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

  private EntityModelResourceWithStatusDTO createResource(
      long id,
      String name,
      String sourceId,
      String organizationName,
      EntityModelResourceWithStatusDTO.CurrentStateEnum state) {
    var resource = new EntityModelResourceWithStatusDTO();
    resource.setId(id);
    resource.setName(name);
    resource.setSourceId(sourceId);
    resource.setCurrentState(state);
    if (organizationName != null) {
      var organization = new OrganizationDTO();
      organization.setName(organizationName);
      resource.setOrganization(organization);
    }
    return resource;
  }
}
