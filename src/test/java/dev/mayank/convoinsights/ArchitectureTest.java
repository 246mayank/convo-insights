package dev.mayank.convoinsights;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** Dependency rules that keep the layers honest; they fail the build when someone takes a shortcut. */
@AnalyzeClasses(packages = "dev.mayank.convoinsights", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT = "dev.mayank.convoinsights";

    @ArchTest
    static final ArchRule domainDependsOnNothingInTheApp = noClasses()
            .that().resideInAPackage(ROOT + ".domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".api..", ROOT + ".ingest..", ROOT + ".extract..", ROOT + ".outbox..",
                    ROOT + ".replay..", ROOT + ".eval..", ROOT + ".privacy..", "org.springframework.kafka..")
            .because("the domain model must not know about transport, storage plumbing or extraction");

    @ArchTest
    static final ArchRule extractionIsTransportFree = noClasses()
            .that().resideInAPackage(ROOT + ".extract..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".api..", ROOT + ".ingest..", ROOT + ".outbox..", "org.springframework.kafka..", "jakarta.persistence..")
            .because("extractors are pure functions of a conversation and must run offline (eval, CI)");

    @ArchTest
    static final ArchRule privacyDependsOnlyOnDomain = noClasses()
            .that().resideInAPackage(ROOT + ".privacy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    ROOT + ".api..", ROOT + ".ingest..", ROOT + ".extract..", ROOT + ".outbox..", "org.springframework.kafka..");

    @ArchTest
    static final ArchRule apiNeverPublishesOrWritesTheOutbox = noClasses()
            .that().resideInAPackage(ROOT + ".api..")
            .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".outbox..", "org.springframework.kafka..")
            .because("the REST layer is read-only; all writes go through the consumer and the outbox");

    @ArchTest
    static final ArchRule onlyTheRelayAndToolsProduceToKafka = noClasses()
            .that().resideOutsideOfPackages(ROOT + ".outbox..", ROOT + ".replay..", ROOT + ".ingest..")
            .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
            .because("insights reach Kafka only through the transactional outbox");

    @ArchTest
    static final ArchRule noFieldInjection = noFields()
            .should().beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .because("constructor injection keeps dependencies explicit and classes testable");

    @ArchTest
    static final ArchRule noPackageCycles = slices().matching(ROOT + ".(*)..").should().beFreeOfCycles();
}
