package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.CreateTicketRequest;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.NegotiationTicketBodyFormatter;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UserResponseModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class NegotiationTicketBodyFormatterTest {

  private static final String NEGOTIATION_HEADER =
      """
          ════════════════════════════════════════════════════════════
            NEGOTIATION [NEGO-1]
          ════════════════════════════════════════════════════════════

            Requester:  adam.researcher@gmail.com
            Email:      adam.researcher@gmail.com
            State:      IN_PROGRESS


          ────────────────────────────────────────────────────────────
            PROJECT
          ────────────────────────────────────────────────────────────

            Title:           HIV Cohort Study
            Disease Code:    B23.0
            Objective:       To investigate disease progression and comorbidities in
                             people living with HIV/AIDS
            Organization:    University Medical Center
            Profit:          No
            Acknowledgment:  Funded by National Institute of Health Research
            Multichoice Test:
              • second_choice
              • third_choice
            Single Test:     second_choice
            Bool Test:       Yes


          ────────────────────────────────────────────────────────────
            REQUEST
          ────────────────────────────────────────────────────────────

            Description:  We seek access to HIV cohort samples with documented
                          disease progression markers and comorbidity data
            Collection:   —
            Donors:       150
            Samples:      serum
            Specifics:    CD4 count and viral load data required


          ────────────────────────────────────────────────────────────
            ETHICS VOTE
          ────────────────────────────────────────────────────────────

            Ethics Vote:             Approved by University Ethics Committee
            Ethics Vote Attachment:  —


          ────────────────────────────────────────────────────────────
            SEARCH PARAMETERS
          ────────────────────────────────────────────────────────────

            (diagnosis EQUALS B23.0)


          """;

  @Nested
  @DisplayName("buildTitle")
  class BuildTitle {

    @Test
    @DisplayName("includes the resource name when one is present")
    void withResourceName_includesResourceNameInTitle() {
      var request =
          new CreateTicketRequest(
              "neg-1", "NEGO-1", "Dummy Collection", "IN_PROGRESS", "SUBMITTED", "");

      assertThat(NegotiationTicketBodyFormatter.buildTitle(request))
          .isEqualTo("[NEGO-1] Dummy Collection — Access Request");
    }

    @Test
    @DisplayName("falls back to the aggregate title when the resource name is null")
    void withNullResourceName_usesAggregateTitle() {
      var request =
          new CreateTicketRequest("neg-1", "NEGO-1", null, "IN_PROGRESS", "SUBMITTED", "");

      assertThat(NegotiationTicketBodyFormatter.buildTitle(request))
          .isEqualTo("[NEGO-1] Resources Access Request");
    }
  }

  @Nested
  @DisplayName("getTicketBodyWithSingleResource")
  class SingleResource {

    @Test
    @DisplayName("renders the full body with a single resource block")
    void returnsExpectedBody() {
      var negotiation = createNegotiation();
      var resource =
          createResource(
              "Dummy test collection", "bbmri-eric:ID:SE_890:collection:dummy_collection");

      String body =
          NegotiationTicketBodyFormatter.getTicketBodyWithSingleResource(negotiation, resource);

      String expected =
          NEGOTIATION_HEADER
              + """
              ────────────────────────────────────────────────────────────
                RESOURCE
              ────────────────────────────────────────────────────────────

                Name:   Dummy test collection
                ID:     bbmri-eric:ID:SE_890:collection:dummy_collection
                State:  SUBMITTED
              """;

      assertThat(body).isEqualTo(expected);
    }
  }

  @Nested
  @DisplayName("getTicketBodyWithMultipleResources")
  class MultipleResources {

    @Test
    @DisplayName("renders all resources with a single shared state line")
    void returnsExpectedBody() {
      var negotiation = createNegotiation();
      var resource1 =
          createResource(
              "Dummy test collection", "bbmri-eric:ID:SE_890:collection:dummy_collection");
      var resource2 =
          createResource(
              "Dummy test collection2", "bbmri-eric:ID:SE_890:collection:dummy_collection2");

      String body =
          NegotiationTicketBodyFormatter.getTicketBodyWithMultipleResources(
              negotiation, List.of(resource1, resource2));

      String expected =
          NEGOTIATION_HEADER
              + """
              ────────────────────────────────────────────────────────────
                RESOURCES
              ────────────────────────────────────────────────────────────

                • Dummy test collection
                  ID: bbmri-eric:ID:SE_890:collection:dummy_collection

                • Dummy test collection2
                  ID: bbmri-eric:ID:SE_890:collection:dummy_collection2

                ──────────────────────────────
                State (all resources):  SUBMITTED
              """;

      assertThat(body).isEqualTo(expected);
    }
  }

  private EntityModelNegotiationDTO createNegotiation() {
    var author = new UserResponseModel();
    author.setName("adam.researcher@gmail.com");
    author.setEmail("adam.researcher@gmail.com");

    Map<String, Object> project = new LinkedHashMap<>();
    project.put("title", "HIV Cohort Study");
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
    negotiation.setDisplayId("NEGO-1");
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
