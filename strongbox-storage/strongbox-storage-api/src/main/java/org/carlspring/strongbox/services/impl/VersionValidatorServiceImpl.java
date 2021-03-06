package org.carlspring.strongbox.services.impl;

import org.carlspring.strongbox.services.VersionValidatorService;
import org.carlspring.strongbox.storage.validation.version.VersionValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author mtodorov
 */
@Component
public class VersionValidatorServiceImpl
        implements VersionValidatorService
{

    @Autowired
    private Set<VersionValidator> versionValidators = new LinkedHashSet<>();


    public VersionValidatorServiceImpl()
    {
    }

    @Override
    public Set<VersionValidator> getVersionValidators()
    {
        return versionValidators;
    }

    public void setVersionValidators(Set<VersionValidator> versionValidators)
    {
        this.versionValidators = versionValidators;
    }

}
