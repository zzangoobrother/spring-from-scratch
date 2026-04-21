package com.choisk.sfs.beans;

public sealed interface CreationStage {
    enum Instantiating implements CreationStage { INSTANCE }
    enum Populating    implements CreationStage { INSTANCE }
    enum Initializing  implements CreationStage { INSTANCE }
}
