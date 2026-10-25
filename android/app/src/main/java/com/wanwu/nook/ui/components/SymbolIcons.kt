package com.wanwu.nook.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icons are stored by SF Symbol name (that's what iOS writes); this maps
 * each name used in the shared catalogs to the closest Material icon.
 */
object SymbolIcons {
    private val map: Map<String, ImageVector> by lazy {
        val o = Icons.Outlined
        mapOf(
            "air.conditioner.horizontal" to o.AcUnit, "air.purifier" to o.Air, "airpods" to o.Headset,
            "airpodsmax" to o.Headphones, "alarm" to o.Alarm, "ant" to o.BugReport, "applepencil" to o.Create,
            "appletv" to o.Tv, "applewatch" to o.Watch, "archivebox" to o.Archive, "backpack" to o.Backpack,
            "bag" to o.ShoppingBag, "balloon" to o.Celebration, "bandage" to o.Healing, "basket" to o.ShoppingBasket,
            "basketball" to o.SportsBasketball, "bathtub" to o.Bathtub, "battery.100" to o.BatteryFull,
            "bed.double" to o.Bed, "bell" to o.Notifications, "bicycle" to o.DirectionsBike, "bird" to o.FlutterDash,
            "birthday.cake" to o.Cake, "bolt" to o.Bolt, "book" to o.MenuBook, "book.closed" to o.Book,
            "bookmark" to o.BookmarkBorder, "books.vertical" to o.LibraryBooks, "briefcase" to o.BusinessCenter,
            "bubbles.and.sparkles" to o.Soap, "cabinet" to o.DoorSliding, "cable.connector" to o.Cable,
            "calendar" to o.CalendarToday, "camera" to o.PhotoCamera, "camera.macro" to o.LocalFlorist,
            "car" to o.DirectionsCar, "carrot" to o.BakeryDining, "cart" to o.ShoppingCart, "cat" to o.Pets,
            "chair" to o.Chair, "chair.lounge" to o.Weekend, "circle.grid.3x3" to o.Apps, "clock" to o.Schedule,
            "comb" to o.Face, "computermouse" to o.Mouse, "cooktop" to o.Countertops, "creditcard" to o.CreditCard,
            "cross.case" to o.MedicalServices, "crown" to o.Diamond, "cube" to o.ViewInAr,
            "cup.and.heat.waves" to o.Coffee, "cup.and.saucer" to o.LocalCafe, "cylinder" to o.LocalDrink,
            "desktopcomputer" to o.DesktopMac, "dice" to o.Casino, "dishwasher" to o.Wash, "display" to o.Monitor,
            "doc.text" to o.Description, "dog" to o.Pets, "door.left.hand.open" to o.DoorFront, "drop" to o.WaterDrop,
            "drop.triangle" to o.OilBarrel, "dryer" to o.DryCleaning, "dumbbell" to o.FitnessCenter,
            "envelope" to o.MailOutline, "externaldrive" to o.Storage, "eyedropper" to o.Colorize,
            "eyeglasses" to o.Visibility, "facemask" to o.Masks, "fan" to o.Air, "figure.pool.swim" to o.Pool,
            "figure.walk" to o.DirectionsWalk, "figure.yoga" to o.SelfImprovement, "film" to o.Movie,
            "fish" to o.SetMeal, "flag" to o.Flag, "flame" to o.LocalFireDepartment, "flashlight.off.fill" to o.FlashlightOn,
            "folder" to o.Folder, "football" to o.SportsFootball, "fork.knife" to o.Restaurant,
            "fork.knife.circle" to o.DinnerDining, "frying.pan" to o.SoupKitchen, "fuelpump" to o.LocalGasStation,
            "gamecontroller" to o.SportsEsports, "gearshape" to o.Settings, "gift" to o.CardGiftcard,
            "giftcard" to o.Redeem, "graduationcap" to o.School, "guitars" to o.MusicNote, "hammer" to o.Hardware,
            "handbag" to o.LocalMall, "hands.sparkles" to o.CleanHands, "hanger" to o.Checkroom, "hare" to o.CrueltyFree,
            "hat.cap" to o.Checkroom, "hat.widebrim" to o.Checkroom, "headphones" to o.Headphones,
            "heart" to o.FavoriteBorder, "heart.fill" to o.Favorite, "hifispeaker" to o.Speaker,
            "highlighter" to o.BorderColor, "homepod" to o.SpeakerGroup, "humidifier" to o.WaterDrop,
            "ipad" to o.TabletMac, "iphone" to o.PhoneIphone, "key" to o.VpnKey, "keyboard" to o.Keyboard,
            "ladybug" to o.BugReport, "lamp.desk" to o.EmojiObjects, "lamp.floor" to o.EmojiObjects,
            "laptopcomputer" to o.LaptopMac, "leaf" to o.Eco, "level" to o.Straighten, "lightbulb" to o.Lightbulb,
            "link" to o.Link, "lock" to o.Lock, "lungs" to o.Air, "magazine" to o.AutoStories,
            "magnifyingglass" to o.Search, "medal" to o.MilitaryTech, "memorychip" to o.Memory, "menucard" to o.MenuBook,
            "microwave" to o.Microwave, "mug" to o.Coffee, "music.note" to o.MusicNote, "newspaper" to o.Article,
            "note.text" to o.StickyNote2, "oven" to o.Kitchen, "paintbrush" to o.Brush, "paintbrush.pointed" to o.Brush,
            "paintpalette" to o.Palette, "paperclip" to o.AttachFile, "party.popper" to o.Celebration,
            "pawprint" to o.Pets, "pencil" to o.Edit, "pencil.and.ruler" to o.Architecture, "pencil.tip" to o.Create,
            "person.text.rectangle" to o.Badge, "pianokeys" to o.Piano, "pills" to o.Medication, "pin" to o.PushPin,
            "popcorn" to o.Fastfood, "powercord" to o.Power, "powerplug" to o.ElectricalServices, "printer" to o.Print,
            "puzzlepiece" to o.Extension, "questionmark.circle" to o.HelpOutline, "rectangle.dashed" to o.CropSquare,
            "rectangle.portrait" to o.CropPortrait, "refrigerator" to o.Kitchen, "ruler" to o.Straighten,
            "scissors" to o.ContentCut, "screwdriver" to o.Build, "sdcard" to o.SdCard, "shippingbox" to o.Inventory2,
            "shippingbox.fill" to o.Inventory, "shoe" to o.DirectionsRun, "shoe.2" to o.DirectionsRun,
            "shoeprints.fill" to o.DirectionsWalk, "shower" to o.Shower, "sink" to o.Wash, "skateboard" to o.Skateboarding,
            "soccerball" to o.SportsSoccer, "sofa" to o.Weekend, "speaker" to o.Speaker, "spoon" to o.Flatware,
            "square.dashed" to o.CropSquare, "square.fill" to o.Stop, "square.grid.2x2" to o.GridView,
            "square.stack.3d.up" to o.Layers, "star" to o.StarBorder, "stethoscope" to o.MonitorHeart,
            "stove" to o.OutdoorGrill, "suitcase" to o.Luggage, "suitcase.rolling" to o.Luggage, "sun.max" to o.WbSunny,
            "sunglasses" to o.Visibility, "syringe" to o.Vaccines, "table.furniture" to o.TableRestaurant,
            "tag" to o.LocalOffer, "takeoutbag.and.cup.and.straw" to o.TakeoutDining,
            "takeoutbag.and.cup.and.straw.fill" to o.LocalDrink, "teddybear" to o.Toys, "tennis.racket" to o.SportsTennis,
            "tennisball" to o.SportsTennis, "tent" to o.Festival, "thermometer.medium" to o.Thermostat,
            "ticket" to o.ConfirmationNumber, "toilet" to o.Wc, "tortoise" to o.Pets, "trash" to o.Delete,
            "tray" to o.Inbox, "tray.2" to o.AllInbox, "tree" to o.Park, "trophy" to o.EmojiEvents,
            "tshirt" to o.Checkroom, "tshirt.fill" to o.Checkroom, "tv" to o.Tv, "umbrella" to o.Umbrella,
            "washer" to o.LocalLaundryService, "watch.analog" to o.Watch, "waterbottle" to o.LocalDrink,
            "wifi.router" to o.Router, "window.casement" to o.Window, "wineglass" to o.WineBar,
            "wineglass.fill" to o.LocalBar, "wrench" to o.Build, "wrench.and.screwdriver" to o.Handyman,
        )
    }

    fun vector(name: String?): ImageVector = map[name] ?: Icons.Outlined.Inventory2

    fun has(name: String) = map.containsKey(name)
}
