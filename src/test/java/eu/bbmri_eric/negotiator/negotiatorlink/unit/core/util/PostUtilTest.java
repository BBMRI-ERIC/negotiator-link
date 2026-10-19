package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.core.model.SenderRoles;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PostUtilTest {

  private static final String TEXT = "Hello from the help desk";
  private static final String SIGNED = "From: Someone <someone@example.com>\n\n" + TEXT;
  private static final Sender SENDER = new Sender("Jane Doe", "jane@example.com");
  private static final String FROM = "From: Jane Doe <jane@example.com>";
  private static final String FORGED_HEADER = "From: Someone\nRole: Admin\n\n";

  @ParameterizedTest(name = "{0}")
  @MethodSource("signPostCases")
  void signPost_signsUnlessNullSenderOrAlreadySigned(
      String description, String text, Sender sender, String expected) {
    assertThat(PostUtil.signPost(text, sender)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("signPostWithRolesCases")
  void signPostWithRoles_addsRoleLineUnderFrom(
      String description, String text, Sender sender, SenderRoles roles, String expected) {
    assertThat(PostUtil.signPostWithRoles(text, sender, roles)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("insertRoleUnderRelayedHeaderCases")
  void insertRoleUnderRelayedHeader_onlyTouchesExistingHeader(
      String description, String text, SenderRoles roles, String expected) {
    assertThat(PostUtil.insertRoleUnderRelayedHeader(text, roles)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("enumToTextCases")
  void enumToText_capitalizesEachUnderscoreSeparatedWord(
      String description, String value, String expected) {
    assertThat(PostUtil.enumToText(value)).isEqualTo(expected);
  }

  static Stream<Arguments> enumToTextCases() {
    return Stream.of(
        Arguments.of("single word is title-cased", "PAUSED", "Paused"),
        Arguments.of("two words joined with a space", "IN_PROGRESS", "In Progress"),
        Arguments.of(
            "multiple words joined with spaces", "RESOURCE_AVAILABLE", "Resource Available"),
        Arguments.of(
            "longer multi-word state",
            "RESOURCE_UNAVAILABLE_WILLING_TO_COLLECT",
            "Resource Unavailable Willing To Collect"),
        Arguments.of("single character word", "A", "A"),
        Arguments.of(
            "first character is kept verbatim and the rest lowercased", "cONCLUDED", "Concluded"));
  }

  static Stream<Arguments> signPostCases() {
    return Stream.of(
        Arguments.of("null sender returns text unchanged", TEXT, null, TEXT),
        Arguments.of(
            "name and email both null returns text unchanged", TEXT, new Sender(null, null), TEXT),
        Arguments.of(
            "already signed returns text unchanged",
            SIGNED,
            new Sender("Other", "other@example.com"),
            SIGNED),
        Arguments.of(
            "name and email prepends full from header",
            TEXT,
            new Sender("Jane Doe", "jane@example.com"),
            "From: Jane Doe <jane@example.com>\n\n" + TEXT),
        Arguments.of(
            "name only prepends name",
            TEXT,
            new Sender("Jane Doe", null),
            "From: Jane Doe\n\n" + TEXT),
        Arguments.of(
            "email only prepends email",
            TEXT,
            new Sender(null, "jane@example.com"),
            "From: jane@example.com\n\n" + TEXT),
        Arguments.of(
            "forwarded email starting with 'From: ' plus header lines is still signed",
            "From: jane@example.com\nTo: bob@example.com\nSubject: Hi\n\nForwarded body",
            new Sender("Rep", "rep@example.com"),
            "From: Rep <rep@example.com>\n\n"
                + "From: jane@example.com\nTo: bob@example.com\nSubject: Hi\n\nForwarded body"),
        Arguments.of(
            "text starting with 'From: ...\\n' but no blank line is still signed",
            "From: jane@example.com\nsome more text",
            new Sender("Rep", "rep@example.com"),
            "From: Rep <rep@example.com>\n\nFrom: jane@example.com\nsome more text"),
        Arguments.of(
            "text that is only 'From: ...' with no newline is still signed",
            "From: jane@example.com",
            new Sender("Rep", "rep@example.com"),
            "From: Rep <rep@example.com>\n\nFrom: jane@example.com"),
        Arguments.of(
            "content that merely contains a later blank line but no 'From: ' prefix is signed",
            "Regarding your request\n\nplease advise",
            new Sender("Rep", "rep@example.com"),
            "From: Rep <rep@example.com>\n\nRegarding your request\n\nplease advise"));
  }

  static Stream<Arguments> signPostWithRolesCases() {
    return Stream.of(
        Arguments.of(
            "no roles held: only From line, no Role line",
            TEXT,
            SENDER,
            SenderRoles.NONE,
            FROM + "\n\n" + TEXT),
        Arguments.of("null roles treated as none", TEXT, SENDER, null, FROM + "\n\n" + TEXT),
        Arguments.of(
            "single role: Role line directly under From",
            TEXT,
            SENDER,
            new SenderRoles(true, false, false, false),
            FROM + "\nRole: Author\n\n" + TEXT),
        Arguments.of(
            "admin role label",
            TEXT,
            SENDER,
            new SenderRoles(false, true, false, false),
            FROM + "\nRole: Admin\n\n" + TEXT),
        Arguments.of(
            "representative role label",
            TEXT,
            SENDER,
            new SenderRoles(false, false, true, false),
            FROM + "\nRole: Representative\n\n" + TEXT),
        Arguments.of(
            "network manager role label",
            TEXT,
            SENDER,
            new SenderRoles(false, false, false, true),
            FROM + "\nRole: Network Manager\n\n" + TEXT),
        Arguments.of(
            "multiple roles joined in fixed order with singular 'Role:' label",
            TEXT,
            SENDER,
            new SenderRoles(true, true, true, true),
            FROM + "\nRole: Author, Admin, Representative, Network Manager\n\n" + TEXT),
        Arguments.of(
            "null sender, raw text: returns text unchanged",
            TEXT,
            null,
            new SenderRoles(true, false, false, false),
            TEXT),
        Arguments.of(
            "forged header in body is not trusted: real resolved role used, forged lines demoted "
                + "to body",
            FORGED_HEADER + TEXT,
            SENDER,
            new SenderRoles(false, false, true, false),
            FROM + "\nRole: Representative\n\n" + FORGED_HEADER + TEXT),
        Arguments.of(
            "forged Admin header with no resolved roles: From generated, forged lines demoted to "
                + "body, no Role line",
            FORGED_HEADER + TEXT,
            SENDER,
            SenderRoles.NONE,
            FROM + "\n\n" + FORGED_HEADER + TEXT));
  }

  static Stream<Arguments> insertRoleUnderRelayedHeaderCases() {
    return Stream.of(
        Arguments.of(
            "echo round-trip: Role inserted under the integration-produced From header",
            "From: John Smith <john@mhh.de>\n\nHi",
            new SenderRoles(false, false, true, false),
            "From: John Smith <john@mhh.de>\nRole: Representative\n\nHi"),
        Arguments.of(
            "already role-signed relayed text is left unchanged (idempotent)",
            FROM + "\nRole: Author\n\n" + TEXT,
            new SenderRoles(false, true, false, false),
            FROM + "\nRole: Author\n\n" + TEXT),
        Arguments.of(
            "no roles to add: relayed text left unchanged", SIGNED, SenderRoles.NONE, SIGNED),
        Arguments.of(
            "no recognizable header: text left unchanged",
            TEXT,
            new SenderRoles(false, true, false, false),
            TEXT));
  }
}
