package android.net

class TestUri(private val uriString: String) : Uri() {
    override fun toString(): String = uriString
    override fun getScheme(): String = if (uriString.contains("://")) uriString.substringBefore("://") else uriString.substringBefore(":")
    override fun getPath(): String = if (uriString.contains("://")) "/" + uriString.substringAfter("://").substringAfter("/", "") else ""
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getSchemeSpecificPart(): String = uriString.substringAfter(":")
    override fun getEncodedSchemeSpecificPart(): String = getSchemeSpecificPart()
    override fun getAuthority(): String = if (uriString.contains("://")) uriString.substringAfter("://").substringBefore("/") else ""
    override fun getEncodedAuthority(): String = getAuthority()
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String = getAuthority()
    override fun getPort(): Int = -1
    override fun getEncodedPath(): String = getPath()
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): List<String> = getPath().split("/").filter { it.isNotEmpty() }
    override fun getLastPathSegment(): String? = getPathSegments().lastOrNull()
    override fun buildUpon(): Builder? = null
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
    override fun compareTo(other: Uri?): Int = uriString.compareTo(other?.toString().orEmpty())
    override fun equals(other: Any?): Boolean = other === this || (other is Uri && other.toString() == this.toString())
    override fun hashCode(): Int = uriString.hashCode()
}
