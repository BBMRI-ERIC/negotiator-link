package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import static j2html.TagCreator.br;
import static j2html.TagCreator.div;
import static j2html.TagCreator.each;
import static j2html.TagCreator.h2;
import static j2html.TagCreator.h3;
import static j2html.TagCreator.h4;
import static j2html.TagCreator.li;
import static j2html.TagCreator.p;
import static j2html.TagCreator.span;
import static j2html.TagCreator.strong;
import static j2html.TagCreator.table;
import static j2html.TagCreator.tbody;
import static j2html.TagCreator.td;
import static j2html.TagCreator.text;
import static j2html.TagCreator.tr;
import static j2html.TagCreator.ul;

import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.OrganizationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UserResponseModel;
import j2html.tags.DomContent;
import j2html.tags.specialized.TableTag;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class NegotiationTicketBodyFormatter {

  private static final String EMPTY_VALUE = "\u2014";
  private static final String UNKNOWN_ORG = "Unknown organization";

  private static final String WRAPPER_STYLE =
      "font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;"
          + "color:#1f2933;line-height:1.5;";
  private static final String TITLE_STYLE =
      "margin:0 0 16px;padding:12px 16px;background:#0b5394;color:#ffffff;"
          + "border-radius:6px;font-size:18px;";
  private static final String SECTION_HEADER_STYLE =
      "margin:24px 0 8px;padding-bottom:4px;border-bottom:2px solid #c9d2dc;"
          + "font-size:14px;text-transform:uppercase;letter-spacing:.5px;color:#334e68;";
  private static final String ORG_SUBHEADING_STYLE =
      "margin:16px 0 6px;font-size:13px;font-weight:600;color:#627d98;";
  private static final String TABLE_STYLE = "border-collapse:collapse;width:100%;";
  private static final String LABEL_CELL_STYLE =
      "padding:4px 12px 4px 0;vertical-align:top;font-weight:600;color:#486581;"
          + "white-space:nowrap;width:1%;";
  private static final String VALUE_CELL_STYLE = "padding:4px 0;vertical-align:top;";
  private static final String MUTED_STYLE = "color:#9aa5b1;";

  // ------------
  // Public API
  // ------------

  /**
   * @return HTML string representing the ticket body for a single resource.
   */
  public static String getTicketBodyWithSingleResource(
      EntityModelNegotiationDTO negotiation,
      EntityModelResourceWithStatusDTO resource,
      List<EntityModelResourceWithStatusDTO> allNegotiationResources) {
    return wrapper(
            commonSections(negotiation),
            sectionHeader("REQUESTED RESOURCES"),
            singleResourceTable(resource),
            otherResourcesSection(List.of(resource), allNegotiationResources))
        .render();
  }

  /**
   * @return HTML string representing the updated ticket body for a single resource.
   */
  public static String getUpdatedTicketBodyWithSingleResource(
      EntityModelNegotiationDTO negotiation,
      EntityModelResourceWithStatusDTO resource,
      List<EntityModelResourceWithStatusDTO> allNegotiationResources) {
    return updateNote()
        + getTicketBodyWithSingleResource(negotiation, resource, allNegotiationResources);
  }

  /**
   * @return HTML string representing the updated ticket body for multiple resources.
   */
  public static String getUpdatedTicketBodyWithMultipleResources(
      EntityModelNegotiationDTO negotiation,
      List<EntityModelResourceWithStatusDTO> resources,
      List<EntityModelResourceWithStatusDTO> allNegotiationResources) {
    return updateNote()
        + getTicketBodyWithMultipleResources(negotiation, resources, allNegotiationResources);
  }

  /**
   * @return HTML string representing the ticket body for multiple resources.
   */
  public static String getTicketBodyWithMultipleResources(
      EntityModelNegotiationDTO negotiation,
      List<EntityModelResourceWithStatusDTO> resources,
      List<EntityModelResourceWithStatusDTO> allNegotiationResources) {
    return wrapper(
            commonSections(negotiation),
            sectionHeader("REQUESTED RESOURCES"),
            resourcesList(resources),
            fieldsTable(List.of(Field.scalar("State (all resources)", sharedState(resources)))),
            otherResourcesSection(resources, allNegotiationResources))
        .render();
  }

  public static String buildTitle(EntityModelNegotiationDTO negotiation, String resourceName) {
    String prefix = "[NEGO-" + negotiation.getDisplayId() + "] ";
    return getTitleIfPresentInPayload(negotiation, resourceName)
        .map(t -> prefix + t)
        .orElseGet(
            () ->
                resourceName != null
                    ? prefix + "Access Request — " + resourceName
                    : prefix + "Resources Access Request");
  }

  // -----------------
  // Section builders
  // -----------------

  private static DomContent wrapper(DomContent... content) {
    return div().withStyle(WRAPPER_STYLE).with(content);
  }

  private static DomContent commonSections(EntityModelNegotiationDTO negotiation) {
    return each(
        titleBlock(negotiation),
        payloadSections(negotiation),
        sectionHeader("SEARCH PARAMETERS"),
        p(text(safe(negotiation.getHumanReadable()))).withStyle("margin:0;"));
  }

  private static DomContent titleBlock(EntityModelNegotiationDTO negotiation) {
    var fields = new ArrayList<Field>();
    UserResponseModel author = negotiation.getAuthor();
    if (author != null) {
      fields.add(Field.scalar("Requester", author.getName()));
      fields.add(Field.scalar("Email", author.getEmail()));
    }
    fields.add(Field.scalar("State", negotiation.getStatus()));
    fields.add(Field.scalar("Negotiation ID", negotiation.getDisplayId()));

    return each(h2("Negotiator Access Request").withStyle(TITLE_STYLE), fieldsTable(fields));
  }

  @SuppressWarnings("unchecked")
  private static DomContent payloadSections(EntityModelNegotiationDTO negotiation) {
    if (!(negotiation.getPayload() instanceof Map<?, ?> payload)) {
      return each();
    }
    var sections = new ArrayList<DomContent>();
    for (Map.Entry<String, Object> section : ((Map<String, Object>) payload).entrySet()) {
      if (section.getValue() instanceof Map<?, ?> sectionFields) {
        sections.add(sectionHeader(toTitle(section.getKey()).toUpperCase()));
        sections.add(payloadSection((Map<String, Object>) sectionFields));
      }
    }
    return each(sections.toArray(new DomContent[0]));
  }

  private static DomContent payloadSection(Map<String, Object> sectionFields) {
    var fields = new ArrayList<Field>();
    for (Map.Entry<String, Object> f : sectionFields.entrySet()) {
      fields.add(Field.of(toTitle(f.getKey()), f.getValue()));
    }
    return fieldsTable(fields);
  }

  private static DomContent singleResourceTable(EntityModelResourceWithStatusDTO resource) {
    return fieldsTable(
        List.of(
            Field.scalar("Name", resource.getName()),
            Field.scalar("ID", resource.getSourceId()),
            Field.scalar("State", stateOf(resource))));
  }

  private static DomContent resourcesList(List<EntityModelResourceWithStatusDTO> resources) {
    return ul().withStyle("margin:0;padding-left:20px;")
        .with(
            resources.stream()
                .map(
                    r ->
                        li(
                                strong(text(safe(r.getName()))),
                                br(),
                                span("ID: " + safe(r.getSourceId())).withStyle(MUTED_STYLE))
                            .withStyle("margin-bottom:8px;")));
  }

  /**
   * Renders the other negotiation's resources that are not requested in this ticket, grouped by
   * organization name.
   */
  private static DomContent otherResourcesSection(
      List<EntityModelResourceWithStatusDTO> ticketResources,
      List<EntityModelResourceWithStatusDTO> allNegotiationResources) {

    Set<Long> ticketResourceIds =
        ticketResources.stream()
            .map(EntityModelResourceWithStatusDTO::getId)
            .collect(Collectors.toSet());
    Set<EntityModelResourceWithStatusDTO> ticketResourceInstances =
        Collections.newSetFromMap(new IdentityHashMap<>());
    ticketResourceInstances.addAll(ticketResources);

    List<EntityModelResourceWithStatusDTO> others =
        allNegotiationResources.stream()
            .filter(r -> !belongsToTicket(r, ticketResourceIds, ticketResourceInstances))
            .toList();

    if (others.isEmpty()) {
      return each();
    }

    Map<String, List<EntityModelResourceWithStatusDTO>> byOrganization =
        others.stream()
            .collect(
                Collectors.groupingBy(
                    NegotiationTicketBodyFormatter::organizationNameOf,
                    LinkedHashMap::new,
                    Collectors.toList()));

    var content = new ArrayList<DomContent>();
    content.add(sectionHeader("OTHER RESOURCES IN THIS NEGOTIATION"));
    byOrganization.forEach(
        (organizationName, organizationResources) -> {
          content.add(organizationSubheading(organizationName));
          content.add(otherResourcesList(organizationResources));
        });
    return each(content.toArray(new DomContent[0]));
  }

  private static boolean belongsToTicket(
      EntityModelResourceWithStatusDTO resource,
      Set<Long> ticketResourceIds,
      Set<EntityModelResourceWithStatusDTO> ticketResourceInstances) {
    if (resource.getId() != null) {
      return ticketResourceIds.contains(resource.getId());
    }
    return ticketResourceInstances.contains(resource);
  }

  private static DomContent organizationSubheading(String organizationName) {
    return h4(text(safe(organizationName))).withStyle(ORG_SUBHEADING_STYLE);
  }

  private static DomContent otherResourcesList(List<EntityModelResourceWithStatusDTO> resources) {
    return ul().withStyle("margin:0;padding-left:20px;")
        .with(
            resources.stream()
                .map(
                    r ->
                        li(
                                strong(text(safe(r.getName()))),
                                br(),
                                span("State: "
                                        + safe(stateOf(r))
                                        + " · ID: "
                                        + safe(r.getSourceId()))
                                    .withStyle(MUTED_STYLE))
                            .withStyle("margin-bottom:8px;")));
  }

  private static String organizationNameOf(EntityModelResourceWithStatusDTO resource) {
    return Optional.ofNullable(resource.getOrganization())
        .map(OrganizationDTO::getName)
        .filter(name -> !name.isBlank())
        .orElse(UNKNOWN_ORG);
  }

  private static TableTag fieldsTable(List<Field> fields) {
    return table()
        .withStyle(TABLE_STYLE)
        .with(
            tbody(
                each(
                    fields,
                    field ->
                        tr(
                            td(field.label()).withStyle(LABEL_CELL_STYLE),
                            td(renderValue(field)).withStyle(VALUE_CELL_STYLE)))));
  }

  private static DomContent renderValue(Field field) {
    if (field.isList()) {
      if (field.bullets().isEmpty()) {
        return span(EMPTY_VALUE).withStyle(MUTED_STYLE);
      }
      return ul().withStyle("margin:0;padding-left:18px;")
          .with(field.bullets().stream().map(b -> li(text(b)))); // text() = escaped
    }
    if (field.value() == null) {
      return span(EMPTY_VALUE).withStyle(MUTED_STYLE);
    }
    return text(field.value()); // text() = escaped
  }

  private static DomContent sectionHeader(String title) {
    return h3(title).withStyle(SECTION_HEADER_STYLE);
  }

  private static String stateOf(EntityModelResourceWithStatusDTO resource) {
    return resource.getCurrentState() != null ? resource.getCurrentState().getValue() : null;
  }

  private static String sharedState(List<EntityModelResourceWithStatusDTO> resources) {
    return resources.stream()
        .map(EntityModelResourceWithStatusDTO::getCurrentState)
        .filter(Objects::nonNull)
        .map(EntityModelResourceWithStatusDTO.CurrentStateEnum::getValue)
        .findFirst()
        .orElse(null);
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  private static String toTitle(String key) {
    String[] words = key.replace("-", " ").split(" ");
    var sb = new StringBuilder();
    for (String word : words) {
      if (word.isEmpty()) continue;
      if (sb.length() > 0) sb.append(' ');
      sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return sb.toString();
  }

  /**
   * Attempts to read a human-readable title from the negotiation payload at {@code
   * payload.project.title}.
   *
   * <p>The payload has no deployment-independent shape. Its structure is determined entirely by
   * each Negotiator instance's configurable access forms, so this {@code project.title} path is an
   * assumption that may not hold everywhere. In that case the generic title is used as a fallback.
   */
  @SuppressWarnings("unchecked")
  private static Optional<String> getTitleIfPresentInPayload(
      EntityModelNegotiationDTO negotiation, String resourceName) {
    if (!(negotiation.getPayload() instanceof Map<?, ?> payload)) return Optional.empty();
    Object project = ((Map<String, Object>) payload).get("project");
    if (!(project instanceof Map<?, ?> projectMap)) return Optional.empty();
    Object title = ((Map<String, Object>) projectMap).get("title");
    if (title == null) return Optional.empty();
    String titleStr = title.toString().trim();
    if (!titleStr.isBlank() && resourceName != null) titleStr = titleStr + " — " + resourceName;
    return titleStr.isEmpty() ? Optional.empty() : Optional.of(titleStr);
  }

  private static String updateNote() {
    return "<p><strong>&#8505; The negotiation has been updated. Current details are shown below.</strong></p>";
  }

  // -----------
  // Field model
  // -----------

  private record Field(String label, List<String> bullets, String value) {
    static Field scalar(String label, String rawValue) {
      String v = rawValue == null ? null : rawValue.trim();
      return new Field(label, null, (v == null || v.isEmpty()) ? null : v);
    }

    static Field list(String label, List<String> bullets) {
      return new Field(label, bullets, null);
    }

    static Field of(String label, Object raw) {
      if (raw instanceof List<?> list) {
        var bullets = new ArrayList<String>(list.size());
        for (Object item : list) bullets.add(String.valueOf(item));
        return list(label, bullets);
      }
      return scalar(label, raw == null ? null : raw.toString());
    }

    boolean isList() {
      return bullets != null;
    }
  }
}
