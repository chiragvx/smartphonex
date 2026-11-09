package com.smartphonex.core

import kotlinx.serialization.json.*
import kotlin.math.max

/**
 * Advisory airspace and no-go layers. They inform and never block: rules are loosely enforced in practice, the data
 * here is incomplete, and only the operator can make sure a flight is allowed. Each notice says where it came from
 * and when, and every one tells the operator to check Digital Sky and the local police. Nothing here ever says a plan
 * is "compliant". Acknowledgements are logged with the time and who acknowledged them.
 *
 * Built in: India's airport bands, computed from each operational airport's reference point (OurAirports, public
 * domain) with an allowance for its perimeter. Under the Drone Rules 2021 airspace map, as published: within 8 km of
 * an airport's perimeter is yellow (controlled, permission needed); from 8 to 12 km, green only up to 200 ft (60 m);
 * elsewhere green up to 400 ft (120 m). Red zones can't be computed: they're imported, traced from Digital Sky.
 */
object Advisories {
    const val NOTICE = "You may need permission. Check Digital Sky and contact your local police to make sure you're compliant."
    const val DISCLAIMER = "Airspace and no-go layers are for information only. They may be incomplete, out of date or wrong, and they don't decide anything. Making sure a flight is allowed is the operator's responsibility: check Digital Sky and contact your local police."
    const val AIRPORT_SOURCE = "Airport bands computed from OurAirports (public domain), 2026-11-09: approximate, from each airport's reference point"

    data class Airport(val code: String, val name: String, val lat: Double, val lon: Double, val size: Char) {
        /** How far the perimeter may be from the reference point, km: a rough allowance for runways and land. */
        val allowanceKm get() = when (size) { 'l' -> 1.5; 'm' -> 1.0; else -> 0.5 }
    }
    data class Feature(val name: String?, val polygon: List<Pair<Double, Double>>)
    /** An imported layer. [level] is red, yellow or nogo. [date] is when its source was looked at. */
    data class Layer(val id: String, val name: String, val source: String, val date: String, val level: String, val features: List<Feature>)
    data class Advisory(val id: String, val level: String, val text: String, val source: String, val lat: Double?, val lon: Double?) {
        fun json() = buildJsonObject { put("id", id); put("level", level); put("text", text); put("source", source); lat?.let { put("lat", it) }; lon?.let { put("lon", it) } }
    }

    /** Roughly India: the built-in airport bands apply here. */
    fun inIndia(lat: Double, lon: Double) = lat in 6.0..37.5 && lon in 68.0..97.5

    fun airportsNear(lat: Double, lon: Double, km: Double) = AIRPORTS.filter { Navigation.distanceM(lat, lon, it.lat, it.lon) <= km * 1000 }

    /**
     * Notices for a path of (lat, lon, height above home) points, and the home it starts from. Airport bands apply
     * in India; imported layers apply anywhere. [flies] is false for ground vehicles and boats: heights don't apply.
     */
    fun forPath(path: List<Triple<Double, Double, Double>>, home: Pair<Double, Double>?, layers: List<Layer>, flies: Boolean): List<Advisory> {
        if (path.isEmpty()) return emptyList()
        val out = ArrayList<Advisory>()
        val (lat0, lon0) = home ?: (path[0].first to path[0].second)
        if (inIndia(lat0, lon0)) {
            for (a in airportsNear(lat0, lon0, 60.0)) {
                var closest = Double.MAX_VALUE
                var at: Triple<Double, Double, Double>? = null
                var highInBand: Triple<Double, Double, Double>? = null
                for (p in path) {
                    val km = Navigation.distanceM(p.first, p.second, a.lat, a.lon) / 1000 - a.allowanceKm
                    if (km < closest) { closest = km; at = p }
                    if (flies && km in 8.0..12.0 && p.third > 60.5 && (highInBand == null || p.third > highInBand.third)) highInBand = p
                }
                val c = at ?: continue
                if (closest < 8) out += Advisory("airport-${a.code}", "yellow", "Within about ${"%.1f".format(max(0.0, closest))} km of ${a.name} (${a.code}): its yellow zone, controlled airspace. $NOTICE", AIRPORT_SOURCE, c.first, c.second)
                else highInBand?.let { h -> out += Advisory("airport-${a.code}-60", "yellow", "Up to ${h.third.toInt()} m high between 8 and 12 km from ${a.name} (${a.code}), where only 60 m (200 ft) is green. $NOTICE", AIRPORT_SOURCE, h.first, h.second) }
            }
            if (flies) path.maxByOrNull { it.third }?.takeIf { it.third > 120.5 }?.let { h ->
                out += Advisory("height-120", "yellow", "Up to ${h.third.toInt()} m high: above the 120 m (400 ft) of green airspace. $NOTICE", "Drone Rules 2021, as published", h.first, h.second)
            }
        }
        val fr = LocalFrame(lat0, lon0)
        val pts = path.map { fr.xy(it.first, it.second) }
        for (l in layers) for ((k, f) in l.features.withIndex()) {
            if (f.polygon.size < 3) continue
            val poly = f.polygon.map { fr.xy(it.first, it.second) }
            val hit = pts.indices.firstOrNull { i -> PlanGeo.inside(pts[i], poly) || (i > 0 && PlanGeo.edgeDist(pts[i - 1], pts[i], poly) == 0.0) } ?: continue
            val what = when (l.level) { "red" -> "a red zone"; "yellow" -> "a yellow zone"; else -> "a no-go area" }
            out += Advisory("layer-${l.id}-$k", l.level, "Enters $what${f.name?.let { ": $it" } ?: ""} (${l.name}). $NOTICE", "${l.source}, ${l.date}", path[hit].first, path[hit].second)
        }
        return out
    }

    fun layerJson(l: Layer, withShapes: Boolean = true) = buildJsonObject {
        put("id", l.id); put("name", l.name); put("source", l.source); put("date", l.date); put("level", l.level); put("count", l.features.size)
        if (withShapes) putJsonArray("features") { l.features.forEach { f -> addJsonObject { put("name", f.name); put("polygon", JsonArray(f.polygon.map { buildJsonArray { add(it.first); add(it.second) } })) } } }
    }

    /** A layer from a ground station or an import. Returns it, or why not. */
    fun parseLayer(o: JsonObject): Pair<Layer?, String?> {
        fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val name = s("name")?.take(80)?.takeIf { it.isNotEmpty() } ?: return null to "a layer needs a name"
        val source = s("source")?.take(200)?.takeIf { it.isNotEmpty() } ?: return null to "say where the layer came from (source)"
        val date = s("date")?.take(30)?.takeIf { it.isNotEmpty() } ?: return null to "say when its source was looked at (date)"
        val level = s("level") ?: "nogo"
        if (level !in setOf("red", "yellow", "nogo")) return null to "level must be red, yellow or nogo"
        val raw = o["features"] as? JsonArray ?: return null to "a layer needs at least one area"
        if (raw.size > 5000) return null to "at most 5000 areas in a layer"
        val feats = raw.mapNotNull { x ->
            val f = x as? JsonObject ?: return@mapNotNull null
            val poly = (f["polygon"] as? JsonArray)?.mapNotNull { p -> (p as? JsonArray)?.let { a -> (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull?.let { la -> (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull?.let { lo -> la to lo } } } } ?: emptyList()
            if (poly.size < 3 || poly.any { it.first !in -90.0..90.0 || it.second !in -180.0..180.0 }) null
            else Feature((f["name"] as? JsonPrimitive)?.contentOrNull?.take(80), poly.take(5000))
        }
        if (feats.isEmpty()) return null to "a layer needs at least one area"
        val id = s("id")?.takeIf { Regex("^[a-zA-Z0-9_.-]{1,40}$").matches(it) } ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "layer" }
        return Layer(id, name, source, date, level, feats) to null
    }

    private fun A(code: String, name: String, lat: Double, lon: Double, size: Char) = Airport(code, name, lat, lon, size)

    /** India's operational airports: large and medium, and small ones with scheduled flights. OurAirports, public domain. */
    val AIRPORTS: List<Airport> by lazy { listOf(
        A("IN-0015", "Baljek Airport", 25.66149, 90.34504, 's'),
        A("IN-0024", "Baramati Airport", 18.22694, 74.59083, 's'),
        A("IN-0369", "Sant Gadge Baba Yavatmal Airport", 20.39578, 78.21627, 'm'),
        A("VAAH", "Sardar Vallabh Patel International Airport", 23.0772, 72.6347, 'l'),
        A("VAAK", "Akola Airport", 20.69893, 77.05688, 'm'),
        A("VAAM", "Amravati  Airport", 20.81461, 77.71776, 'm'),
        A("VAAU", "Aurangabad Airport", 19.86287, 75.39631, 'm'),
        A("VABB", "Chhatrapati Shivaji Maharaj International Airport", 19.0887, 72.8679, 'l'),
        A("VABJ", "Bhuj Airport", 23.2878, 69.6702, 'm'),
        A("VABO", "Vadodara International Airport", 22.3362, 73.2263, 'l'),
        A("VABP", "Raja Bhoj International Airport", 23.2875, 77.3374, 'l'),
        A("VABV", "Bhavnagar Airport", 21.7522, 72.1852, 'm'),
        A("VADN", "Daman Airport", 20.4344, 72.8432, 'm'),
        A("VADU", "Diu Airport", 20.71419, 70.92185, 's'),
        A("VAGD", "Gondia Airport", 21.52682, 80.29035, 'm'),
        A("VAHS", "Rajkot International Airport", 22.37882, 71.03939, 'l'),
        A("VAID", "Devi Ahilya Bai Holkar International Airport", 22.7214, 75.80051, 'l'),
        A("VAJB", "Jabalpur Airport", 23.1778, 80.052, 'm'),
        A("VAJL", "Jalgaon Airport", 20.96268, 75.62749, 's'),
        A("VAJM", "Jamnagar Airport", 22.4655, 70.0126, 'm'),
        A("VAKE", "Kandla Airport", 23.1127, 70.1003, 'm'),
        A("VAKP", "Kolhapur Airport", 16.6647, 74.2894, 'm'),
        A("VAKS", "Keshod Airport", 21.3171, 70.2704, 'm'),
        A("VALT", "Murod Kond Airport", 18.4115, 76.4647, 'm'),
        A("VAND", "Nanded Airport", 19.1833, 77.3167, 'm'),
        A("VANM", "Navi Mumbai International Airport", 18.9846, 73.06525, 'l'),
        A("VANP", "Dr. Babasaheb Ambedkar International Airport", 21.0922, 79.0472, 'l'),
        A("VAOZ", "Nashik International Airport", 20.1191, 73.9129, 'l'),
        A("VAPO", "Pune International Airport", 18.5821, 73.9197, 'l'),
        A("VAPR", "Porbandar Airport", 21.64952, 69.65641, 'm'),
        A("VARG", "Ratnagiri Airport", 17.0136, 73.3278, 'm'),
        A("VARK", "Rajkot Airport", 22.3092, 70.7795, 'm'),
        A("VASD", "Shirdi International Airport", 19.68921, 74.37365, 'l'),
        A("VASL", "Solapur Airport", 17.628, 75.9348, 'm'),
        A("VASU", "Surat International Airport", 21.11553, 72.74325, 'l'),
        A("VAUD", "Maharana Pratap Airport", 24.6177, 73.8961, 'm'),
        A("VEAB", "Prayagraj Airport", 25.4401, 81.7339, 'm'),
        A("VEAN", "Along Airport", 28.1753, 94.802, 'm'),
        A("VEAP", "Maa Mahamaya Airport", 22.98753, 83.19612, 'm'),
        A("VEAT", "Agartala - Maharaja Bir Bikram Airport", 23.887, 91.2404, 'm'),
        A("VEAY", "Maharshi Valmiki International Airport", 26.74774, 82.16366, 'm'),
        A("VEBD", "Bagdogra Airport", 26.6812, 88.3286, 'l'),
        A("VEBI", "Shillong Airport", 25.7036, 91.9787, 'm'),
        A("VEBN", "Lal Bahadur Shastri International Airport", 25.45217, 82.86255, 'l'),
        A("VEBS", "Biju Patnaik International Airport", 20.25102, 85.81475, 'l'),
        A("VEBU", "Bilaspur Airport", 21.9884, 82.111, 'm'),
        A("VECC", "Netaji Subhash Chandra Bose International Airport", 22.65401, 88.44765, 'l'),
        A("VECX", "Kanpur Airport", 26.4043, 80.4101, 'm'),
        A("VEDB", "Dhanbad Airport", 23.834, 86.4253, 'm'),
        A("VEDG", "Kazi Nazrul Islam Airport", 23.6225, 87.243, 'm'),
        A("VEDH", "Darbhanga Airport", 26.1928, 85.9169, 'm'),
        A("VEDO", "Deoghar Airport", 24.44684, 86.70495, 's'),
        A("VEGK", "Gorakhpur Airport", 26.7397, 83.4497, 'm'),
        A("VEGT", "Lokpriya Gopinath Bordoloi International Airport", 26.10665, 91.58523, 'l'),
        A("VEGY", "Gaya Airport", 24.7443, 84.9512, 'm'),
        A("VEHO", "Itanagar Donyi Polo Hollongi Airport", 26.96683, 93.63879, 'm'),
        A("VEHX", "Hashimara Air Force Station", 26.6983, 89.3691, 'm'),
        A("VEIM", "Bir Tikendrajit International Airport", 24.76, 93.8967, 'l'),
        A("VEJH", "Jharsuguda Airport", 21.9135, 84.0504, 's'),
        A("VEJR", "Jagdalpur Airport", 19.0743, 82.0368, 's'),
        A("VEJS", "Sonari Airport", 22.81371, 86.16898, 'm'),
        A("VEJT", "Jorhat Airport", 26.73046, 94.17542, 'm'),
        A("VEKI", "Kushinagar International Airport", 26.77653, 83.88921, 'm'),
        A("VEKO", "Khajuraho Airport", 24.8172, 79.9186, 'm'),
        A("VEKR", "Kailashahar Airport", 24.30866, 92.00768, 'm'),
        A("VEKU", "Silchar Airport", 24.9129, 92.9787, 'm'),
        A("VELP", "Lengpui Airport", 23.8406, 92.6197, 'm'),
        A("VELR", "Lilabari North Lakhimpur Airport", 27.29568, 94.09727, 'm'),
        A("VEMN", "Dibrugarh Airport", 27.4839, 95.0169, 'm'),
        A("VEMR", "Dimapur Airport", 25.8839, 93.7711, 'm'),
        A("VEMZ", "Muzaffarpur Airport", 26.1191, 85.3137, 'm'),
        A("VEPH", "Panagarh Air Force Station", 23.4743, 87.4275, 'm'),
        A("VEPT", "Jay Prakash Narayan Airport", 25.5913, 85.088, 'm'),
        A("VERC", "Birsa Munda Airport", 23.3143, 85.3217, 'm'),
        A("VERK", "Rourkela Airport", 22.25657, 84.81519, 'm'),
        A("VERP", "Swami Vivekananda Airport", 21.1804, 81.7388, 'm'),
        A("VERW", "Rewa Airport, Chorhata, REWA", 24.5034, 81.2203, 'm'),
        A("VETZ", "Tezpur Airport", 26.7091, 92.7847, 'm'),
        A("VEUK", "Utkela Airport", 20.09778, 83.18333, 'm'),
        A("VEZO", "Ziro Airport", 27.5883, 93.8281, 'm'),
        A("VIAG", "Agra Airport / Agra Air Force Station", 27.15798, 77.96103, 'm'),
        A("VIAM", "Ambala Air Force Station", 30.3681, 76.8167, 'm'),
        A("VIAR", "Sri Guru Ram Das Ji International Airport", 31.7096, 74.7973, 'l'),
        A("VIAX", "Adampur Airport", 31.4338, 75.7588, 's'),
        A("VIBK", "Nal Airport", 28.0706, 73.2072, 'm'),
        A("VIBR", "Kullu Manali Airport", 31.8767, 77.1544, 'm'),
        A("VIBT", "Bhatinda Air Force Station", 30.2701, 74.7558, 'm'),
        A("VIBY", "Bareilly Air Force Station", 28.4221, 79.4508, 'm'),
        A("VICG", "Shaheed Bhagat Singh International Airport", 30.6735, 76.7885, 'l'),
        A("VIDD", "Safdarjung Airport", 28.58545, 77.20356, 'm'),
        A("VIDN", "Dehradun Jolly Grant Airport", 30.18924, 78.17665, 'm'),
        A("VIDP", "Indira Gandhi International Airport", 28.55563, 77.09519, 'l'),
        A("VIDX", "Hindon Airport / Hindon Air Force Station", 28.7077, 77.3589, 's'),
        A("VIGG", "Kangra Airport", 32.1649, 76.26302, 'm'),
        A("VIGR", "Gwalior Airport", 26.2933, 78.2278, 'm'),
        A("VIHR", "Maharaja Agrasen International Airport", 29.18606, 75.74142, 'l'),
        A("VIHX", "Halwara International Airport", 30.7485, 75.6298, 'l'),
        A("VIJO", "Jodhpur Airport", 26.2511, 73.0489, 'm'),
        A("VIJP", "Jaipur International Airport", 26.8242, 75.8122, 'l'),
        A("VIJR", "Jaisalmer Airport", 26.8887, 70.865, 'm'),
        A("VIJU", "Jammu Airport", 32.68885, 74.83815, 'm'),
        A("VIKG", "Kishangarh Airport Ajmer", 26.59101, 74.81296, 'm'),
        A("VIKO", "Kota Airport", 25.1602, 75.8456, 'm'),
        A("VILD", "Ludhiana Airport", 30.8547, 75.9526, 'm'),
        A("VILH", "Leh Kushok Bakula Rimpochee Airport", 34.1359, 77.5465, 'm'),
        A("VILK", "Chaudhary Charan Singh International Airport", 26.7606, 80.8893, 'l'),
        A("VIMB", "Moradabad Airport", 28.81746, 78.92187, 'm'),
        A("VIND", "Noida International Airport", 28.17987, 77.61184, 'l'),
        A("VIPK", "Pathankot Airport", 32.23361, 75.63444, 'm'),
        A("VIPT", "Pantnagar Airport", 29.0334, 79.4737, 'm'),
        A("VISA", "Sirsa Air Force Station", 29.5606, 75.0061, 'm'),
        A("VISR", "Srinagar International Airport", 33.9871, 74.7742, 'l'),
        A("VISV", "Shravasti Airport", 27.49973, 82.03293, 's'),
        A("VIUX", "Udhampur Air Force Station", 32.90215, 75.15571, 'm'),
        A("VOAT", "Agatti Airport", 10.8237, 72.176, 'm'),
        A("VOBG", "HAL Airport", 12.95, 77.6682, 'm'),
        A("VOBI", "Bellary Airport", 15.1628, 76.8828, 'm'),
        A("VOBL", "Kempegowda International Airport Bengaluru", 13.1979, 77.7063, 'l'),
        A("VOBM", "Belagavi Airport", 15.8593, 74.6183, 'm'),
        A("VOBR", "Bidar Airport / Bidar Air Force Station", 17.9081, 77.4871, 'm'),
        A("VOBX", "Campbell Bay Airport / INS Baaz", 7.01304, 93.9228, 'm'),
        A("VOBZ", "Vijayawada International Airport", 16.53001, 80.80489, 'l'),
        A("VOCB", "Coimbatore International Airport", 11.03, 77.0434, 'l'),
        A("VOCI", "Cochin International Airport", 10.15105, 76.40084, 'l'),
        A("VOCL", "Calicut International Airport", 11.136, 75.95515, 'l'),
        A("VOCP", "Kadapa Airport", 14.51315, 78.76918, 'm'),
        A("VOCX", "Car Nicobar Air Force Base", 9.15306, 92.81927, 'm'),
        A("VODG", "Dundigul Air Force Academy", 17.6272, 78.4034, 'm'),
        A("VOGA", "Manohar International Airport", 15.74426, 73.86062, 'l'),
        A("VOGB", "Kalaburagi Airport", 17.30815, 76.96525, 's'),
        A("VOGO", "Goa Dabolim International Airport", 15.38006, 73.83333, 'l'),
        A("VOHB", "Hubballi Airport", 15.36108, 75.0821, 'm'),
        A("VOHS", "Rajiv Gandhi International Airport", 17.23132, 78.42986, 'l'),
        A("VOHY", "Begumpet Airport", 17.4531, 78.4676, 'm'),
        A("VOKN", "Kannur International Airport", 11.91634, 75.54498, 'l'),
        A("VOKU", "Kurnool Airport", 15.71629, 78.16923, 'm'),
        A("VOMD", "Madurai Airport", 9.83451, 78.0934, 'm'),
        A("VOML", "Mangaluru International Airport", 12.95471, 74.88681, 'l'),
        A("VOMM", "Chennai International Airport", 12.99001, 80.1693, 'l'),
        A("VOMY", "Mysore Airport", 12.22975, 76.65368, 'm'),
        A("VOPB", "Veer Savarkar International Airport / INS Utkrosh", 11.64019, 92.72902, 'l'),
        A("VOPC", "Pondicherry Airport", 11.968, 79.812, 'm'),
        A("VOPN", "Sri Sathya Sai Airport", 14.1493, 77.7911, 'm'),
        A("VORY", "Rajahmundry Airport", 17.1058, 81.8132, 'm'),
        A("VOSH", "Rashtrakavi Kuvempu Airport", 13.85802, 75.61887, 'm'),
        A("VOSM", "Salem Airport", 11.7833, 78.0656, 'm'),
        A("VOSR", "Sindhudurg Airport", 16.00255, 73.52985, 'm'),
        A("VOTK", "Tuticorin Airport", 8.72424, 78.0258, 's'),
        A("VOTP", "Tirupati International Airport", 13.63199, 79.53987, 'l'),
        A("VOTR", "Tiruchirappalli International Airport", 10.76291, 78.71774, 'l'),
        A("VOTV", "Thiruvananthapuram International Airport", 8.48189, 76.92003, 'l'),
        A("VOVI", "Alluri Sitarama Raju International Airport (Vizag)", 17.97151, 83.50362, 'l'),
        A("VOVZ", "INS Dega Airport (visakhapatnam international airport)", 17.72351, 83.22773, 'm'),
    ) }
}
