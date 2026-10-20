package eu.bbmri_eric.negotiator.negotiatorlink.unit.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import eu.bbmri_eric.negotiator.negotiatorlink.core.model.Sender;
import eu.bbmri_eric.negotiator.negotiatorlink.core.util.PostUtil;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PostUtilTest {

  private static final String TEXT = "Hello from the help desk";
  private static final String SIGNED = "From: Someone <someone@example.com>\n\n" + TEXT;

  @ParameterizedTest(name = "{0}")
  @MethodSource("signPostCases")
  void signPost_signsUnlessNullSenderOrAlreadySigned(
      String description, String text, Sender sender, String expected) {
    assertThat(PostUtil.signPost(text, sender)).isEqualTo(expected);
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
}
