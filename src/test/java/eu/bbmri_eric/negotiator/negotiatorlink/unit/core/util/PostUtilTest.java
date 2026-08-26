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
            "From: jane@example.com\n\n" + TEXT));
  }
}
