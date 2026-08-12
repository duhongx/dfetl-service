package com.dfygt.dfetl.server.service;

import com.dfygt.dfetl.server.entity.DfetlDataset;
import com.dfygt.dfetl.server.entity.DfetlField;
import com.dfygt.dfetl.server.entity.Institution;
import com.dfygt.dfetl.server.entity.InstitutionDatasetRoute;
import com.dfygt.dfetl.server.entity.SourceDataSource;
import com.dfygt.dfetl.server.entity.TargetDataSource;
import java.util.List;

public record ResolvedDatasetRoute(
        Institution institution,
        DfetlDataset dataset,
        InstitutionDatasetRoute route,
        SourceDataSource source,
        TargetDataSource target,
        List<DfetlField> fields) {
}
