import {
    createAppContainer,
    createSwitchNavigator,
} from "react-navigation";

import { createStackNavigator } from 'react-navigation-stack'
import LoginComponent from "../modules/loginScreens/LoginComponent";
import SelectDetailsComponent from "../modules/myScanScreens/SelectDetailsComponent";
import OdishaScanComponent from "../modules/myScanScreens/OdishaScanComponent";
import OdishaPatScanDetailsComponent from "../modules/myScanScreens/OdishaPatScanDetailsComponent";


const AuthStack = createStackNavigator({
    login: {
        screen: LoginComponent
    }   
}, {
    initialRouteName: 'login',
    headerMode: 'none'
})

const MainStack = createStackNavigator(
    { 
        selectDetails: {
            screen: SelectDetailsComponent
        },
        odishaScan: {
            screen: OdishaScanComponent
        },
        odishaPatScanDetails: {
            screen: OdishaPatScanDetailsComponent
        }
    },
    {
        initialRouteName: 'selectDetails',
        headerMode: 'none'
    }
)

const AppNavigation = createSwitchNavigator(
    {
        auth: AuthStack,
        mainMenu: MainStack,
    },
    {
        initialRouteName: 'auth',
    }
);

export default (AppContainer = createAppContainer(AppNavigation));
