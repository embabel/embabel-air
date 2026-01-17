package com.embabel.air;

import com.embabel.air.backend.Customer;
import com.embabel.air.backend.FlightSegment;
import com.embabel.air.backend.Reservation;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.Action;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

/**
 * Generates DDL schema from JPA entities.
 * Run explicitly with: mvn test -Dtest=GenerateDdlTest
 * Disabled by default to prevent overwriting schema.sql during normal test runs.
 */
@Disabled("Run explicitly to regenerate schema.sql")
class GenerateDdlTest {

    @Test
    void generateDdl() throws IOException {
        var outputDir = Path.of("src/main/resources/db");
        Files.createDirectories(outputDir);
        var outputFile = outputDir.resolve("schema.sql").toAbsolutePath().toString();

        var settings = new HashMap<String, Object>();
        settings.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        settings.put("hibernate.format_sql", "true");
        settings.put("hibernate.physical_naming_strategy", CamelCaseToUnderscoresNamingStrategy.class.getName());
        settings.put("jakarta.persistence.schema-generation.scripts.action", Action.CREATE.getExternalHbm2ddlName());
        settings.put("jakarta.persistence.schema-generation.scripts.create-target", outputFile);

        var registry = new StandardServiceRegistryBuilder()
                .applySettings(settings)
                .build();

        try {
            var metadata = new MetadataSources(registry)
                    .addAnnotatedClass(Customer.class)
                    .addAnnotatedClass(Reservation.class)
                    .addAnnotatedClass(FlightSegment.class)
                    .getMetadataBuilder()
                    .applyPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy())
                    .build();

            SchemaManagementToolCoordinator.process(
                    metadata,
                    registry,
                    settings,
                    null
            );

            System.out.println("DDL generated at: " + outputFile);
        } finally {
            StandardServiceRegistryBuilder.destroy(registry);
        }
    }
}
