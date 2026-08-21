package eu.bbmri_eric.negotiator.negotiatorlink.core.util;

import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelNegotiationDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.EntityModelResourceWithStatusDTO;
import eu.bbmri_eric.negotiator.negotiatorlink.generated.model.UserResponseModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders a negotiation into a plain-text help desk ticket body.
 *
 * <p>The output is intended to be displayed in a monospace / preformatted view, so column alignment
 * and box-drawing dividers are used to create a readable hierarchy without relying on rich text.
 */
@Slf4j
public final class NegotiationTicketBodyFormatter {

  private static final String EMPTY_VALUE = "—";
  private static final int WRAP_WIDTH = 55;
  private static final String DOUBLE_RULE =
      "═══════════════════════════════════════════════════════";
  private static final String SECTION_RULE =
      "───────────────────────────────────────────────────────";
  private static final String RESOURCE_RULE = "──────────────────────────────";

  private NegotiationTicketBodyFormatter() {}

  /** Builds the ticket body for a single-resource (PER_RESOURCE) ticket. */
  public static String getTicketBodyWithSingleResource(
      EntityModelNegotiationDTO negotiation, EntityModelResourceWithStatusDTO resource) {
    var body = new BodyBuilder();
    appendCommonSections(body, negotiation);
    appendSingleResourceSection(body, resource);
    return body.build();
  }

  /** Builds the ticket body for a multi-resource (PER_NEGOTIATION) ticket. */
  public static String getTicketBodyWithMultipleResources(
      EntityModelNegotiationDTO negotiation, List<EntityModelResourceWithStatusDTO> resources) {
    var body = new BodyBuilder();
    appendCommonSections(body, negotiation);
    appendMultipleResourcesSection(body, resources);
    return body.build();
  }

  /** Builds the ticket title. */
  public static String buildTitle(EntityModelNegotiationDTO negotiation, String resourceName) {
    String prefix = "[NEGO-" + negotiation.getDisplayId() + "] ";
    Optional<String> title = getTitleIfPresentInPayload(negotiation, resourceName);
    return title
        .map(negTitle -> prefix + negTitle)
        .orElseGet(
            () ->
                resourceName != null
                    ? prefix + "Access Request — " + resourceName
                    : prefix + "Resources Access Request");
  }

  @SuppressWarnings("unchecked")
  private static Optional<String> getTitleIfPresentInPayload(
      EntityModelNegotiationDTO negotiation, String resourceName) {
    if (!(negotiation.getPayload() instanceof Map<?, ?> payload)) {
      return Optional.empty();
    }
    Object project = ((Map<String, Object>) payload).get("project");
    if (!(project instanceof Map<?, ?> projectMap)) {
      return Optional.empty();
    }
    Object title = ((Map<String, Object>) projectMap).get("title");
    if (title == null) {
      return Optional.empty();
    }
    String titleStr = title.toString().trim();
    if (!titleStr.isBlank() && resourceName != null) {
      titleStr = titleStr + " — " + resourceName;
    }
    return titleStr.isEmpty() ? Optional.empty() : Optional.of(titleStr);
  }

  // ---------------------------------------------------------------------------
  // Section assembly
  // ---------------------------------------------------------------------------

  private static void appendCommonSections(
      BodyBuilder body, EntityModelNegotiationDTO negotiation) {
    appendTitleBlock(body, negotiation);
    appendPayloadSections(body, negotiation);
    appendSearchParameters(body, negotiation);
  }

  private static void appendTitleBlock(BodyBuilder body, EntityModelNegotiationDTO negotiation) {
    body.line(DOUBLE_RULE);
    body.line("  Negotiator Access Request");
    body.line(DOUBLE_RULE);
    body.blank();

    var fields = new Fields();
    UserResponseModel author = negotiation.getAuthor();
    if (author != null) {
      fields.add("Requester", author.getName());
      fields.add("Email", author.getEmail());
    }
    fields.add("State", negotiation.getStatus());
    fields.add("Negotiation ID", negotiation.getDisplayId());
    body.append(fields.render());
  }

  @SuppressWarnings("unchecked")
  private static void appendPayloadSections(
      BodyBuilder body, EntityModelNegotiationDTO negotiation) {
    if (!(negotiation.getPayload() instanceof Map<?, ?> payload)) {
      return;
    }
    for (Map.Entry<String, Object> section : ((Map<String, Object>) payload).entrySet()) {
      if (section.getValue() instanceof Map<?, ?> sectionFields) {
        appendPayloadSection(body, section.getKey(), (Map<String, Object>) sectionFields);
      }
    }
  }

  private static void appendPayloadSection(
      BodyBuilder body, String sectionKey, Map<String, Object> sectionFields) {
    body.sectionHeader(toTitle(sectionKey).toUpperCase());

    var fields = new Fields();
    for (Map.Entry<String, Object> field : sectionFields.entrySet()) {
      fields.add(toTitle(field.getKey()), field.getValue());
    }
    body.append(fields.render());
  }

  private static void appendSearchParameters(
      BodyBuilder body, EntityModelNegotiationDTO negotiation) {
    body.sectionHeader("SEARCH PARAMETERS");
    body.line("  " + negotiation.getHumanReadable());
  }

  private static void appendSingleResourceSection(
      BodyBuilder body, EntityModelResourceWithStatusDTO resource) {
    body.sectionHeader("RESOURCE");

    var fields = new Fields();
    fields.add("Name", resource.getName());
    fields.add("ID", resource.getSourceId());
    fields.add("State", stateOf(resource));
    body.append(fields.render());
  }

  private static void appendMultipleResourcesSection(
      BodyBuilder body, List<EntityModelResourceWithStatusDTO> resources) {
    body.sectionHeader("RESOURCES");

    for (EntityModelResourceWithStatusDTO resource : resources) {
      body.line("  • " + resource.getName());
      body.line("    ID: " + resource.getSourceId());
      body.blank();
    }

    body.line("  " + RESOURCE_RULE);
    body.line("  State (all resources):  " + sharedState(resources));
  }

  // ---------------------------------------------------------------------------
  // Value helpers
  // ---------------------------------------------------------------------------

  private static String stateOf(EntityModelResourceWithStatusDTO resource) {
    return resource.getCurrentState() != null ? resource.getCurrentState().getValue() : EMPTY_VALUE;
  }

  private static String sharedState(List<EntityModelResourceWithStatusDTO> resources) {
    return resources.stream()
        .map(EntityModelResourceWithStatusDTO::getCurrentState)
        .filter(Objects::nonNull)
        .map(EntityModelResourceWithStatusDTO.CurrentStateEnum::getValue)
        .findFirst()
        .orElse(EMPTY_VALUE);
  }

  /**
   * Converts a payload key such as {@code disease-code} into a display label {@code Disease Code}.
   */
  private static String toTitle(String key) {
    String[] words = key.replace("-", " ").split(" ");
    var sb = new StringBuilder();
    for (String word : words) {
      if (word.isEmpty()) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append(' ');
      }
      sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------
  // Field rendering (aligned columns + wrapping + bulleted lists)
  // ---------------------------------------------------------------------------

  /** A field is either a scalar value (aligned in a column) or a bulleted list. */
  private record Field(String label, List<String> bullets, String value) {
    static Field scalar(String label, String value) {
      return new Field(label, null, value);
    }

    static Field list(String label, List<String> bullets) {
      return new Field(label, bullets, null);
    }

    boolean isList() {
      return bullets != null;
    }
  }

  /** Accumulates fields for one section, then renders them with a shared column width. */
  private static final class Fields {
    private final List<Field> fields = new ArrayList<>();

    void add(String label, Object rawValue) {
      if (rawValue instanceof List<?> list) {
        var bullets = new ArrayList<String>(list.size());
        for (Object item : list) {
          bullets.add(String.valueOf(item));
        }
        fields.add(Field.list(label, bullets));
      } else {
        fields.add(Field.scalar(label, normalize(rawValue)));
      }
    }

    /** Renders every field, aligning scalar values under a common column. */
    String render() {
      int labelColumn = scalarLabelWidth();
      var sb = new StringBuilder();
      for (Field field : fields) {
        if (field.isList()) {
          renderList(sb, field);
        } else {
          renderScalar(sb, field, labelColumn);
        }
      }
      return sb.toString();
    }

    /** The value column starts after the longest {@code "Label: "} among scalar fields. */
    private int scalarLabelWidth() {
      int max = 0;
      for (Field field : fields) {
        if (!field.isList()) {
          max = Math.max(max, field.label().length() + 1); // + 1 for the colon
        }
      }
      return max;
    }

    private void renderList(StringBuilder sb, Field field) {
      sb.append("  ").append(field.label()).append(":\n");
      for (String bullet : field.bullets()) {
        sb.append("    • ").append(bullet).append('\n');
      }
    }

    private void renderScalar(StringBuilder sb, Field field, int labelColumn) {
      String labelPart = field.label() + ":";
      String indent = "  ";
      String valueIndent = indent + " ".repeat(labelColumn + 2); // colon width + 2 spaces gap

      List<String> lines = wrap(field.value(), WRAP_WIDTH);
      for (int i = 0; i < lines.size(); i++) {
        if (i == 0) {
          sb.append(indent)
              .append(padRight(labelPart, labelColumn + 2))
              .append(lines.get(i))
              .append('\n');
        } else {
          sb.append(valueIndent).append(lines.get(i)).append('\n');
        }
      }
    }

    private static String normalize(Object value) {
      if (value == null) {
        return EMPTY_VALUE;
      }
      String str = value.toString().trim();
      return str.isEmpty() ? EMPTY_VALUE : str;
    }

    private static String padRight(String text, int width) {
      return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    /** Word-wraps text to the given width, never breaking a single word. */
    private static List<String> wrap(String text, int width) {
      var lines = new ArrayList<String>();
      var current = new StringBuilder();
      for (String word : text.split(" ")) {
        if (current.length() == 0) {
          current.append(word);
        } else if (current.length() + 1 + word.length() <= width) {
          current.append(' ').append(word);
        } else {
          lines.add(current.toString());
          current.setLength(0);
          current.append(word);
        }
      }
      if (current.length() > 0 || lines.isEmpty()) {
        lines.add(current.toString());
      }
      return lines;
    }
  }

  // ---------------------------------------------------------------------------
  // Low-level line accumulation
  // ---------------------------------------------------------------------------

  /** Thin wrapper over {@link StringBuilder} that guarantees consistent newline handling. */
  private static final class BodyBuilder {
    private final StringBuilder sb = new StringBuilder();

    void line(String text) {
      sb.append(text).append('\n');
    }

    void blank() {
      sb.append('\n');
    }

    void append(String block) {
      sb.append(block);
    }

    void sectionHeader(String title) {
      blank();
      blank();
      line(SECTION_RULE);
      line("  " + title);
      line(SECTION_RULE);
      blank();
    }

    String build() {
      return sb.toString();
    }
  }
}
