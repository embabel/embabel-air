package com.embabel.air.backend;

import com.embabel.agent.rag.model.NamedEntity;
import org.jspecify.annotations.NonNull;

public interface Airport extends NamedEntity {

    @NonNull String getCode();
}
