```java

 InstalledUnit source = new InstalledUnit(
                skill.name(), skill.version(), kind, InstalledUnit.InstallSource.UNKNOWN,
                origin, hash, gitRef, UnitStore.nowIso(), null,
                dev.skillmanager.model.UnitKind.SKILL);
                
```

So we can probably do a bit of interpolation here. For instance, we can call the registry and see if it's exactly same SHA as verison in config.toml? Or see if it's in the registry?

Additionally, in reconcile we could also do something similar.


---
