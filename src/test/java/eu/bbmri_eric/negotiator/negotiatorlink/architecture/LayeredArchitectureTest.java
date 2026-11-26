package eu.bbmri_eric.negotiator.negotiatorlink.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the project's open (relaxed) layered architecture. */
@AnalyzeClasses(
    packages = "eu.bbmri_eric.negotiator.negotiatorlink",
    importOptions = ImportOption.DoNotIncludeTests.class)
class LayeredArchitectureTest {

  private static final String INBOUND = "..negotiatorlink.inbound..";
  private static final String CORE = "..negotiatorlink.core..";
  private static final String ADAPTER = "..negotiatorlink.adapter..";
  private static final String COMMON = "..negotiatorlink.common..";

  @ArchTest
  static final ArchRule openLayeringIsRespected =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Inbound")
          .definedBy(INBOUND)
          .layer("Core")
          .definedBy(CORE)
          .layer("Adapter")
          .definedBy(ADAPTER)
          .layer("Common")
          .definedBy(COMMON)
          // Who is allowed to depend on each layer
          .whereLayer("Inbound")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Core")
          .mayOnlyBeAccessedByLayers("Inbound")
          .whereLayer("Adapter")
          .mayOnlyBeAccessedByLayers("Inbound", "Core")
          .whereLayer("Common")
          .mayOnlyBeAccessedByLayers("Inbound", "Core", "Adapter");

  @ArchTest
  static final ArchRule commonDependsOnNothingAbove =
      noClasses()
          .that()
          .resideInAPackage(COMMON)
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(INBOUND, CORE, ADAPTER)
          .because("common is the lowest layer and must not depend on any layer above it");
}
